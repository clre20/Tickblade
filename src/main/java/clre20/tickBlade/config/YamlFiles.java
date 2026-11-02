package clre20.tickBlade.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.BiConsumer;

/**
 * 載入 config.yml / messages.yml，並自動補齊缺少的項目。
 * 使用者的檔案格式錯誤時不會覆寫，改用內建預設值並回報錯誤。
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    /** 載入結果 */
    public record Loaded(YamlConfiguration config, String error, boolean updated) {
    }

    public static Loaded load(JavaPlugin plugin, String name, String versionKey) {
        YamlConfiguration defaults = loadDefaults(plugin, name);
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
            return new Loaded(defaults, null, false);
        }

        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
            return new Loaded(defaults, msg, false);
        }

        boolean missing = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!user.contains(key)) {
                missing = true;
                break;
            }
        }
        // 舊版留下、預設檔已沒有的項目也要清掉
        if (!missing) {
            for (String key : user.getKeys(true)) {
                if (!user.isConfigurationSection(key) && !defaults.contains(key)) {
                    missing = true;
                    break;
                }
            }
        }
        if (!missing && user.getInt(versionKey, -1) == defaults.getInt(versionKey, -1)) {
            user.setDefaults(defaults);
            return new Loaded(user, null, false);
        }

        // 以預設檔（含註解）為底，填回使用者原本的值；預設檔已沒有的項目移除
        YamlConfiguration merged = loadDefaults(plugin, name);
        for (String key : user.getKeys(true)) {
            if (user.isConfigurationSection(key)) continue;
            if (key.equals(versionKey)) continue;
            // 預設檔已沒有的項目（舊版留下的）不保留
            if (!defaults.contains(key) || defaults.isConfigurationSection(key)) continue;
            merged.set(key, user.get(key));
        }
        try {
            Files.copy(file.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            merged.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot write " + name + ": " + e.getMessage());
        }
        merged.setDefaults(defaults);
        return new Loaded(merged, null, true);
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin, String name) {
        YamlConfiguration c = new YamlConfiguration();
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return c;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                c.load(r);
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Built-in " + name + " is broken: " + e.getMessage());
        }
        return c;
    }

    /** 方便回報用 */
    public static void report(Loaded loaded, String name, BiConsumer<String, String> errorLog, BiConsumer<String, String> updatedLog) {
        if (loaded.error() != null) errorLog.accept(name, loaded.error());
        else if (loaded.updated()) updatedLog.accept(name, name);
    }
}
