package ru.holyworld.tntbuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Один быстрый цикл:
 * 1) ставим Тнт-Пушку туда, куда смотрит прицел;
 * 2) открываем её и кладём Динамит B;
 * 3) ставим рядом редстоун блок - пушка стреляет один раз;
 * 4) сразу берём кирку и ломаем раздатчик.
 */
public final class Macro {

    /** Пока true - миксин не даёт ваниле сбрасывать прогресс ломания блока. */
    public static volatile boolean suppressCancel = false;

    private enum Step {
        IDLE,
        PLACE_CANNON, PRE_REDSTONE,
        OPEN_FOR_FILL, WAIT_GUI_FILL, FILL, FILL_PUT, FILL_BACK, CLOSE_AFTER_FILL,
        PLACE_REDSTONE, BREAK
    }

    private record Spot(BlockPos target, BlockPos support, Direction face, Vec3d hit) {
    }

    private static final int EQUIP_NOT_FOUND = 0;
    private static final int EQUIP_READY = 1;
    private static final int EQUIP_SELECTED = 2;
    private static final int EQUIP_SWAPPED = 3;

    private static final double REACH = 4.5;

    /** Блоки, на которые нельзя "опираться" кликом - они откроют GUI или сработают. */
    private static final Set<Block> INTERACTIVE = Set.of(
            Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.LOOM, Blocks.NOTE_BLOCK, Blocks.DISPENSER, Blocks.DROPPER
    );

    private Step step = Step.IDLE;
    private int wait;
    private int stepTicks;
    private int fireWait;

    private int fillSrc;
    private int fillDst;
    private int fillLeft;
    private int openRetries;
    private int airTicks;
    private int equipTries;
    private boolean miningStarted;

    private BlockHitResult startHit;
    private BlockPos cannonPos;
    private BlockPos redstonePos;
    private List<Spot> spots;
    private final Deque<BlockPos> breakQueue = new ArrayDeque<>();

    // ------------------------------------------------------------------ управление

    public void toggle(MinecraftClient mc) {
        if (step != Step.IDLE) {
            ClientPlayerEntity p = mc.player;
            if (p != null) {
                closeGuiIfOpen(p);
                say(p, "Остановлено.", Formatting.YELLOW, false);
            }
            reset(mc);
            return;
        }
        start(mc);
    }

    private void start(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        HitResult hr = mc.crosshairTarget;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult)) {
            say(p, "Наведи прицел на блок, куда нужно поставить пушку.", Formatting.RED, false);
            return;
        }
        if (p.isSneaking()) {
            say(p, "Отпусти Shift: с Shift пушка не откроется.", Formatting.RED, false);
            return;
        }
        // проверяем все предметы заранее, чтобы не остановиться на полпути
        if (!has(p, Macro::isCannon)) {
            say(p, "В инвентаре нет Тнт-Пушки.", Formatting.RED, false);
            return;
        }
        if (!has(p, Macro::isTnt)) {
            say(p, "В инвентаре нет Динамита B.", Formatting.RED, false);
            return;
        }
        if (!has(p, s -> s.isOf(Items.REDSTONE_BLOCK))) {
            say(p, "В инвентаре нет редстоун блока.", Formatting.RED, false);
            return;
        }
        if (!has(p, Macro::isPickaxe)) {
            say(p, "В инвентаре нет кирки.", Formatting.RED, false);
            return;
        }
        reset(mc);
        startHit = (BlockHitResult) hr;
        goTo(Step.PLACE_CANNON, 0);
    }

    private void reset(MinecraftClient mc) {
        suppressCancel = false;
        step = Step.IDLE;
        wait = 0;
        stepTicks = 0;
        fireWait = 0;
        fillSrc = -1;
        fillDst = -1;
        fillLeft = 0;
        openRetries = 0;
        airTicks = 0;
        equipTries = 0;
        miningStarted = false;
        startHit = null;
        cannonPos = null;
        redstonePos = null;
        spots = null;
        breakQueue.clear();
        if (mc != null && mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
    }

    private void goTo(Step next, int delay) {
        step = next;
        wait = Math.max(0, delay);
        stepTicks = 0;
        equipTries = 0;
    }

    private void fail(MinecraftClient mc, ClientPlayerEntity p, String msg) {
        say(p, msg, Formatting.RED, false);
        closeGuiIfOpen(p);
        reset(mc);
    }

    private void finish(MinecraftClient mc, ClientPlayerEntity p) {
        say(p, "Готово.", Formatting.GREEN, true);
        reset(mc);
    }

    // ------------------------------------------------------------------ тик

    public void tick(MinecraftClient mc) {
        if (step == Step.IDLE) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null || !p.isAlive()) {
            reset(mc);
            return;
        }
        if (fireWait > 0) {
            fireWait--;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        // Шаги без ожидания выполняются подряд в одном тике (максимальная скорость).
        int guard = 0;
        while (step != Step.IDLE && wait == 0 && guard++ < 10) {
            // если игрок открыл что-то своё (чат, инвентарь) - пауза
            if (!isGuiStep(step) && mc.currentScreen != null) {
                return;
            }
            Step before = step;
            stepTicks++;
            try {
                run(mc, p, w);
            } catch (Throwable t) {
                HolyWorldTntBuilder.LOGGER.error("Ошибка в макросе", t);
                fail(mc, p, "Внутренняя ошибка: " + t);
                return;
            }
            if (step == before) {
                break; // шаг ждёт следующего тика
            }
        }
    }

    private static boolean isGuiStep(Step s) {
        return s == Step.WAIT_GUI_FILL || s == Step.FILL || s == Step.FILL_PUT
                || s == Step.FILL_BACK || s == Step.CLOSE_AFTER_FILL;
    }

    private void run(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        Config cfg = HolyWorldTntBuilder.CONFIG;
        ClientPlayerInteractionManager im = mc.interactionManager;

        switch (step) {

            // ---------------------------------------------------------- пушка
            case PLACE_CANNON -> {
                if (!ensureHeld(mc, p, Macro::isCannon, "Тнт-Пушка", false)) {
                    return;
                }
                if (p.getEyePos().distanceTo(startHit.getPos()) > REACH + 1.0) {
                    fail(mc, p, "Слишком далеко от выбранного блока. Подойди ближе и запусти снова.");
                    return;
                }
                cannonPos = new ItemPlacementContext(p, Hand.MAIN_HAND, p.getMainHandStack(), startHit).getBlockPos();
                im.interactBlock(p, Hand.MAIN_HAND, startHit);
                p.swingHand(Hand.MAIN_HAND);
                goTo(Step.PRE_REDSTONE, 0);
            }

            // редстоун блок берём в руку сразу, пока пушка ещё "ставится"
            case PRE_REDSTONE -> {
                if (!ensureHeld(mc, p, s -> s.isOf(Items.REDSTONE_BLOCK), "редстоун блок", false)) {
                    return;
                }
                openRetries = 0;
                goTo(Step.OPEN_FOR_FILL, 0);
            }

            // ---------------------------------------------------------- загрузка динамита
            case OPEN_FOR_FILL -> {
                if (!w.getBlockState(cannonPos).isOf(Blocks.DISPENSER)) {
                    fail(mc, p, "Пушка не поставилась (место занято или сервер запретил).");
                    return;
                }
                if (p.isSneaking()) {
                    fail(mc, p, "Отпусти Shift: с Shift пушка не откроется.");
                    return;
                }
                if (p.getEyePos().distanceTo(Vec3d.ofCenter(cannonPos)) > REACH) {
                    fail(mc, p, "Слишком далеко от пушки.");
                    return;
                }
                useCannon(mc, p);
                goTo(Step.WAIT_GUI_FILL, 0);
            }

            case WAIT_GUI_FILL -> {
                if (guiOpen(p)) {
                    goTo(Step.FILL, 0);
                } else if (stepTicks > 30) {
                    if (++openRetries > 2) {
                        fail(mc, p, "Окно пушки не открылось (пушка не поставилась / Shift / далеко).");
                    } else {
                        goTo(Step.OPEN_FOR_FILL, 1);
                    }
                }
            }

            case FILL -> {
                if (!guiOpen(p)) {
                    fail(mc, p, "Окно пушки закрылось раньше времени.");
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                int cs = containerSize(h);
                int src = -1;
                for (int i = cs; i < h.slots.size(); i++) {
                    if (isTnt(h.slots.get(i).getStack())) {
                        src = i;
                        break;
                    }
                }
                if (src < 0) {
                    // содержимое инвентаря могло ещё не прийти от сервера - чуть подождём
                    if (stepTicks <= 6) {
                        return;
                    }
                    fail(mc, p, "Динамит B не найден в инвентаре (ищу \"" + cfg.tntName + "\").");
                    return;
                }
                int dst = -1;
                for (int i = 0; i < cs; i++) {
                    if (h.slots.get(i).getStack().isEmpty()) {
                        dst = i;
                        break;
                    }
                }
                if (dst < 0) {
                    fail(mc, p, "В пушке нет свободного слота.");
                    return;
                }
                if (h.slots.get(src).getStack().getCount() <= cfg.tntPerCannon) {
                    // стак маленький - одним шифт-кликом
                    im.clickSlot(h.syncId, src, 0, SlotActionType.QUICK_MOVE, p);
                    goTo(Step.CLOSE_AFTER_FILL, cfg.clickDelayTicks);
                    return;
                }
                // большой стак: берём на курсор, кладём нужное количество по одному, возвращаем
                fillSrc = src;
                fillDst = dst;
                fillLeft = cfg.tntPerCannon;
                im.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
                goTo(Step.FILL_PUT, cfg.clickDelayTicks);
            }

            case FILL_PUT -> {
                if (!guiOpen(p)) {
                    fail(mc, p, "Окно пушки закрылось раньше времени.");
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                do {
                    im.clickSlot(h.syncId, fillDst, 1, SlotActionType.PICKUP, p);
                    fillLeft--;
                } while (fillLeft > 0 && cfg.clickDelayTicks == 0);
                if (fillLeft > 0) {
                    wait = cfg.clickDelayTicks;
                } else {
                    goTo(Step.FILL_BACK, cfg.clickDelayTicks);
                }
            }

            case FILL_BACK -> {
                if (!guiOpen(p)) {
                    fail(mc, p, "Окно пушки закрылось раньше времени.");
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                im.clickSlot(h.syncId, fillSrc, 0, SlotActionType.PICKUP, p);
                goTo(Step.CLOSE_AFTER_FILL, cfg.clickDelayTicks);
            }

            case CLOSE_AFTER_FILL -> {
                if (guiOpen(p)) {
                    boolean has = containerHasTnt(p.currentScreenHandler);
                    p.closeHandledScreen();
                    if (!has) {
                        fail(mc, p, "Динамит не лёг в пушку. Проверь tntName в конфиге.");
                        return;
                    }
                }
                goTo(Step.PLACE_REDSTONE, 0);
            }

            // ---------------------------------------------------------- запуск
            case PLACE_REDSTONE -> {
                if (!ensureHeld(mc, p, s -> s.isOf(Items.REDSTONE_BLOCK), "редстоун блок", false)) {
                    return;
                }
                if (spots == null) {
                    spots = findRedstoneSpots(w, p);
                }
                if (spots.isEmpty()) {
                    fail(mc, p, "Рядом с пушкой нет места для редстоун блока.");
                    return;
                }
                Spot s = spots.remove(0);
                im.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(s.hit(), s.face(), s.support(), false));
                p.swingHand(Hand.MAIN_HAND);
                redstonePos = s.target();
                fireWait = cfg.fireDelayTicks;
                miningStarted = false;
                breakQueue.clear();
                breakQueue.add(cannonPos);
                if (cfg.breakRedstoneBlock) {
                    breakQueue.add(redstonePos);
                }
                airTicks = 0;
                goTo(Step.BREAK, 0);
            }

            // ---------------------------------------------------------- ломаем раздатчик
            case BREAK -> {
                // кирку берём сразу, пока раздатчик ещё "стреляет"
                if (!ensureHeld(mc, p, Macro::isPickaxe, "кирка", true)) {
                    suppressCancel = false;
                    return;
                }
                BlockPos t = breakQueue.peek();
                if (t == null) {
                    finish(mc, p);
                    return;
                }
                BlockState st = w.getBlockState(t);
                if (st.isAir()) {
                    // ждём пару тиков: вдруг сервер вернёт блок обратно
                    if (++airTicks >= 2) {
                        breakQueue.poll();
                        airTicks = 0;
                        stepTicks = 0;
                        suppressCancel = false;
                        im.cancelBlockBreaking();
                    }
                    return;
                }
                airTicks = 0;

                if (t.equals(cannonPos)) {
                    // пока ломаем пушку, редстоун блок обязан стоять, иначе выстрела не будет
                    if (!w.getBlockState(redstonePos).isOf(Blocks.REDSTONE_BLOCK)) {
                        suppressCancel = false;
                        im.cancelBlockBreaking();
                        miningStarted = false;
                        if (spots != null && !spots.isEmpty()) {
                            goTo(Step.PLACE_REDSTONE, 0);
                        } else {
                            fail(mc, p, "Редстоун блок не поставился (сервер не разрешил).");
                        }
                        return;
                    }
                    if (!miningStarted) {
                        // начинаем ломать заранее: блок сломается не раньше, чем через fireDelayTicks
                        float delta = st.calcBlockBreakingDelta(p, w, t);
                        int need = delta <= 0 ? 0 : (int) Math.ceil(1.0f / delta);
                        if (fireWait > need) {
                            return;
                        }
                        miningStarted = true;
                        stepTicks = 0;
                    }
                }

                Vec3d eye = p.getEyePos();
                Vec3d c = Vec3d.ofCenter(t);
                if (eye.distanceTo(c) > REACH + 0.5) {
                    fail(mc, p, "Слишком далеко, чтобы сломать блок. Подойди ближе.");
                    return;
                }
                if (stepTicks > cfg.breakTimeoutTicks) {
                    fail(mc, p, "Не получается сломать блок (возможно, сервер запрещает).");
                    return;
                }
                suppressCancel = true;
                Direction dir = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
                im.updateBlockBreakingProgress(t, dir);
                p.swingHand(Hand.MAIN_HAND);
            }

            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ вспомогательное

    private void useCannon(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(cannonPos);
        Direction face = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        Vec3d hit = c.add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, cannonPos, false));
    }

    /** Ищет места для редстоун блока рядом с пушкой (не перед её "дулом"). */
    private List<Spot> findRedstoneSpots(ClientWorld w, ClientPlayerEntity p) {
        List<Spot> out = new ArrayList<>();
        BlockState cs = w.getBlockState(cannonPos);
        Direction front = cs.contains(Properties.FACING) ? cs.get(Properties.FACING) : null;
        Vec3d eye = p.getEyePos();

        Direction[] targets = {
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN
        };
        Direction[] supports = {
                Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP
        };

        for (Direction d : targets) {
            if (d == front) {
                continue;
            }
            BlockPos n = cannonPos.offset(d);
            if (!w.getBlockState(n).isReplaceable()) {
                continue;
            }
            if (p.getBoundingBox().intersects(new Box(n))) {
                continue;
            }
            for (Direction f : supports) {
                BlockPos b = n.offset(f);
                if (b.equals(cannonPos)) {
                    continue;
                }
                BlockState bs = w.getBlockState(b);
                if (bs.isAir() || bs.hasBlockEntity() || INTERACTIVE.contains(bs.getBlock())
                        || !bs.isFullCube(w, b)) {
                    continue;
                }
                Direction face = f.getOpposite();
                Vec3d hit = Vec3d.ofCenter(b).add(
                        face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                if (eye.distanceTo(hit) > REACH) {
                    continue;
                }
                out.add(new Spot(n, b, face, hit));
                break;
            }
        }
        return out;
    }

    /**
     * true - предмет в руке, можно действовать прямо сейчас.
     * false - ошибка (макрос остановлен) или нужно подождать тик (только если needsTickSync).
     * needsTickSync = true нужен, если следующим действием не будет interactBlock
     * (он сам отправляет серверу выбранный слот), например для ломания.
     */
    private boolean ensureHeld(MinecraftClient mc, ClientPlayerEntity p, Predicate<ItemStack> pred,
                               String what, boolean needsTickSync) {
        int r = equip(mc, p, pred);
        if (r == EQUIP_READY) {
            return true;
        }
        if (r == EQUIP_NOT_FOUND) {
            fail(mc, p, "Не могу взять в руку: " + what + ".");
            return false;
        }
        if (!pred.test(p.getMainHandStack())) {
            // предмет ещё не оказался в руке - даём паузу и пробуем снова
            if (++equipTries > 5) {
                fail(mc, p, "Не могу взять в руку: " + what + ".");
            } else {
                wait = 1;
            }
            return false;
        }
        if (r == EQUIP_SELECTED && needsTickSync) {
            wait = 1;
            return false;
        }
        return true;
    }

    /** Берёт нужный предмет в руку (из хотбара - выбирает слот, из инвентаря - меняет местами). */
    private int equip(MinecraftClient mc, ClientPlayerEntity p, Predicate<ItemStack> pred) {
        if (pred.test(p.getMainHandStack())) {
            return EQUIP_READY;
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.getStack(i))) {
                inv.selectedSlot = i;
                return EQUIP_SELECTED;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (pred.test(inv.getStack(i))) {
                mc.interactionManager.clickSlot(
                        p.playerScreenHandler.syncId, i, inv.selectedSlot, SlotActionType.SWAP, p);
                return EQUIP_SWAPPED;
            }
        }
        return EQUIP_NOT_FOUND;
    }

    private static boolean has(ClientPlayerEntity p, Predicate<ItemStack> pred) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (pred.test(inv.getStack(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean guiOpen(ClientPlayerEntity p) {
        return p.currentScreenHandler != null && p.currentScreenHandler != p.playerScreenHandler;
    }

    private static void closeGuiIfOpen(ClientPlayerEntity p) {
        if (guiOpen(p)) {
            p.closeHandledScreen();
        }
    }

    /** Размер контейнера = все слоты минус 36 слотов инвентаря игрока (для раздатчика = 9). */
    private static int containerSize(ScreenHandler h) {
        return Math.max(0, h.slots.size() - 36);
    }

    private static boolean containerHasTnt(ScreenHandler h) {
        int cs = containerSize(h);
        for (int i = 0; i < cs; i++) {
            if (h.slots.get(i).getStack().isOf(Items.TNT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCannon(ItemStack s) {
        if (s.isEmpty() || !s.isOf(Items.DISPENSER)) {
            return false;
        }
        String need = norm(HolyWorldTntBuilder.CONFIG.cannonName);
        return need.isEmpty() || norm(s.getName().getString()).contains(need);
    }

    private static boolean isTnt(ItemStack s) {
        if (s.isEmpty() || !s.isOf(Items.TNT)) {
            return false;
        }
        String need = norm(HolyWorldTntBuilder.CONFIG.tntName);
        return need.isEmpty() || norm(s.getName().getString()).contains(need);
    }

    private static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && Registries.ITEM.getId(s.getItem()).getPath().endsWith("_pickaxe");
    }

    private static final String CYR = "\u0430\u0432\u0435\u043a\u043c\u043d\u043e\u0440\u0441\u0442\u0445\u0443\u0451";
    private static final String LAT = "abekmhopctxye";

    /** Нижний регистр, без пробелов, кириллические буквы-двойники заменены латинскими (В == B). */
    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            int idx = CYR.indexOf(c);
            sb.append(idx >= 0 ? LAT.charAt(idx) : c);
        }
        return sb.toString();
    }

    private static void say(ClientPlayerEntity p, String msg, Formatting color, boolean overlay) {
        p.sendMessage(Text.literal("[TNT Builder] " + msg).formatted(color), overlay);
    }
}
