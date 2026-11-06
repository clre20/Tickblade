package clre20.tickBlade.util;

import clre20.tickBlade.config.Settings;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.UUID;

/**
 * Protection.isForeign 的結果快取（只在主執行緒使用）。
 * 其他插件的標記幾乎不會改變，每隻生物 1 分鐘只判斷一次，
 * 避免每輪掃描都對每個 metadata 鍵組字串查詢。只存 UUID。
 */
public final class ForeignCache {

    private static final long TTL_TICKS = 1200;
    private static final int MAX_SIZE = 50_000;

    /** 值 = (到期 tick << 1) | 是否為其他插件的生物 */
    private final HashMap<UUID, Long> cache = new HashMap<>();
    private long lastPurge;

    public boolean isForeign(Entity e, Settings s, long now) {
        UUID id = e.getUniqueId();
        Long v = cache.get(id);
        if (v != null && (v >> 1) > now) return (v & 1L) == 1L;
        boolean foreign = Protection.isForeign(e, s);
        if (cache.size() >= MAX_SIZE) cache.clear();
        cache.put(id, ((now + TTL_TICKS) << 1) | (foreign ? 1L : 0L));
        if (now - lastPurge >= TTL_TICKS) {
            lastPurge = now;
            cache.values().removeIf(x -> (x >> 1) <= now);
        }
        return foreign;
    }

    public void clear() {
        cache.clear();
    }
}
