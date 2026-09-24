package ru.holyworld.tntbuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки лежат в .minecraft/config/holyworld-tntbuilder.json */
public final class Config {
    private static final int VERSION = 3;

    /** Версия формата конфига (старые конфиги сбрасываются на новые значения). */
    public int configVersion = VERSION;

    /** Часть названия предмета "Тнт-Пушка" (регистр не важен). */
    public String cannonName = "\u0442\u043d\u0442-\u043f\u0443\u0448\u043a\u0430";
    /** Часть названия предмета "Динамит B" (латинская B и русская В считаются одинаковыми). */
    public String tntName = "\u0434\u0438\u043d\u0430\u043c\u0438\u0442 b";

    /** Сколько штук динамита класть в пушку (она стреляет 1 раз, остальное не нужно). */
    public int tntPerCannon = 1;

    /**
     * Не раньше чем через сколько тиков после установки редстоун блока пушка может быть сломана.
     * Раздатчик стреляет через 4 тика после подачи сигнала, поэтому меньше 5 ставить нельзя.
     * Мод начинает ломать заранее, с расчётом что блок сломается ровно после этого времени.
     */
    public int fireDelayTicks = 5;

    /** Ломать ли редстоун блок после пушки (чтобы он вернулся в инвентарь). */
    public boolean breakRedstoneBlock = false;

    /** Пауза между кликами по слотам в тиках. 0 = все клики в один тик (быстрее всего). */
    public int clickDelayTicks = 0;
    /** Таймаут ломания одного блока (тики). */
    public int breakTimeoutTicks = 200;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Config load() {
        Config cfg = new Config();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("holyworld-tntbuilder.json");
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    Config loaded = GSON.fromJson(r, Config.class);
                    if (loaded != null && loaded.configVersion == VERSION) {
                        cfg = loaded;
                    }
                }
            }
            cfg.sanitize();
            Files.writeString(path, GSON.toJson(cfg));
        } catch (Exception e) {
            HolyWorldTntBuilder.LOGGER.error("Не удалось прочитать/записать конфиг, используются значения по умолчанию", e);
            cfg = new Config();
        }
        return cfg;
    }

    private void sanitize() {
        configVersion = VERSION;
        if (cannonName == null) cannonName = "";
        if (tntName == null) tntName = "";
        tntPerCannon = Math.max(1, Math.min(64, tntPerCannon));
        fireDelayTicks = Math.max(5, fireDelayTicks);
        clickDelayTicks = Math.max(0, Math.min(5, clickDelayTicks));
        breakTimeoutTicks = Math.max(40, breakTimeoutTicks);
    }
}
