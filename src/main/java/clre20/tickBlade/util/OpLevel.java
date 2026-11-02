package clre20.tickBlade.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 讀取 ops.json 的 OP 等級（Paper API 只有「是否為 OP」，沒有等級）。
 * 結果快取在記憶體；只有檔案修改時間改變時才重新讀取。
 */
public final class OpLevel {

    private static final Path FILE = Paths.get("ops.json");

    private static Map<UUID, Integer> levels = new HashMap<>();
    private static long lastModified = Long.MIN_VALUE;

    private OpLevel() {
    }

    /** 主控台或不需要檢查（required <= 0）時一律通過 */
    public static boolean atLeast(Player p, int required) {
        if (required <= 0) return true;
        if (!p.isOp()) return false;
        return level(p.getUniqueId()) >= required;
    }

    private static synchronized int level(UUID id) {
        long mod;
        try {
            mod = Files.getLastModifiedTime(FILE).toMillis();
        } catch (IOException e) {
            return 0;
        }
        if (mod != lastModified) {
            levels = read();
            lastModified = mod;
        }
        return levels.getOrDefault(id, 0);
    }

    private static Map<UUID, Integer> read() {
        Map<UUID, Integer> out = new HashMap<>();
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonArray()) return out;
            JsonArray arr = root.getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("uuid") || !o.has("level")) continue;
                try {
                    out.put(UUID.fromString(o.get("uuid").getAsString()), o.get("level").getAsInt());
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return out;
    }
}
