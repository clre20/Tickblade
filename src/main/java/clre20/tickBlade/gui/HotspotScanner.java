package clre20.tickBlade.gui;

import clre20.tickBlade.TickBlade;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 掃描已載入區塊的實體、方塊實體、漏斗數量。
 * 只有開介面時才掃描；實體一次統計完，方塊實體分散到多個 tick；結果快取一段時間。
 */
public final class HotspotScanner {

    /** 一個區塊的統計（只存座標與數字） */
    public static final class Entry {
        public final String world;
        public final int cx;
        public final int cz;
        public int entities;
        public int tiles;
        public int hoppers;
        public final Map<EntityType, Integer> types = new EnumMap<>(EntityType.class);
        /** 數量最多的實體所在位置，傳送用 */
        public double sx = Double.NaN;
        public double sy;
        public double sz;
        private final Map<EntityType, double[]> firstPos = new EnumMap<>(EntityType.class);

        Entry(String world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
        }

        void finish() {
            EntityType top = null;
            int best = 0;
            for (var e : types.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    top = e.getKey();
                }
            }
            if (top != null) {
                double[] p = firstPos.get(top);
                sx = p[0];
                sy = p[1];
                sz = p[2];
            }
            firstPos.clear();
        }

        public List<Map.Entry<EntityType, Integer>> topTypes(int n) {
            List<Map.Entry<EntityType, Integer>> list = new ArrayList<>(types.entrySet());
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            return list.size() > n ? list.subList(0, n) : list;
        }
    }

    private record ChunkRef(String world, int cx, int cz) {
    }

    private final TickBlade plugin;
    private List<Entry> result = Collections.emptyList();
    private long scannedAt;
    private boolean scanning;
    private final Set<UUID> waiters = new LinkedHashSet<>();
    private BukkitTask task;

    public HotspotScanner(TickBlade plugin) {
        this.plugin = plugin;
    }

    public List<Entry> result() {
        return result;
    }

    public long scannedAt() {
        return scannedAt;
    }

    /** 開介面：有快取就直接開，否則掃描完再開 */
    public void open(Player p, boolean forceRescan) {
        long age = System.currentTimeMillis() - scannedAt;
        // 連點「重新掃描」時，至少間隔 5 秒才真的重掃
        if (forceRescan && scannedAt != 0 && age < 5000) forceRescan = false;
        if (!forceRescan && scannedAt != 0 && (age < plugin.settings().hotspotCacheSeconds * 1000L || age < 5000)) {
            HotspotMenu.open(plugin, p, HotspotMenu.Mode.ENTITIES, 0);
            return;
        }
        if (scanning) {
            waiters.add(p.getUniqueId());
            plugin.messages().send(p, "hotspots.busy");
            return;
        }
        waiters.add(p.getUniqueId());
        plugin.messages().send(p, "hotspots.scanning");
        startScan();
    }

    private void startScan() {
        scanning = true;
        Map<String, Map<Long, Entry>> byWorld = new HashMap<>();
        List<ChunkRef> chunks = new ArrayList<>();
        for (World w : plugin.getServer().getWorlds()) {
            Map<Long, Entry> map = new HashMap<>();
            byWorld.put(w.getName(), map);
            for (Entity en : w.getEntities()) {
                if (en instanceof Player) continue;
                int cx = ((int) Math.floor(en.getX())) >> 4;
                int cz = ((int) Math.floor(en.getZ())) >> 4;
                Entry e = map.computeIfAbsent(key(cx, cz), k -> new Entry(w.getName(), cx, cz));
                e.entities++;
                e.types.merge(en.getType(), 1, Integer::sum);
                e.firstPos.computeIfAbsent(en.getType(), t -> new double[]{en.getX(), en.getY(), en.getZ()});
            }
            for (Chunk c : w.getLoadedChunks()) chunks.add(new ChunkRef(w.getName(), c.getX(), c.getZ()));
        }
        int perTick = plugin.settings().hotspotChunksPerTick;
        int[] idx = {0};
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int end = Math.min(chunks.size(), idx[0] + perTick);
            for (; idx[0] < end; idx[0]++) {
                ChunkRef ref = chunks.get(idx[0]);
                World w = plugin.getServer().getWorld(ref.world());
                if (w == null || !w.isChunkLoaded(ref.cx(), ref.cz())) continue;
                BlockState[] tiles = w.getChunkAt(ref.cx(), ref.cz()).getTileEntities(false);
                if (tiles.length == 0) continue;
                Entry e = byWorld.get(ref.world()).computeIfAbsent(key(ref.cx(), ref.cz()),
                        k -> new Entry(ref.world(), ref.cx(), ref.cz()));
                e.tiles += tiles.length;
                for (BlockState bs : tiles) if (bs instanceof Hopper) e.hoppers++;
            }
            if (idx[0] >= chunks.size()) {
                finish(byWorld);
            }
        }, 1L, 1L);
    }

    private void finish(Map<String, Map<Long, Entry>> byWorld) {
        if (task != null) task.cancel();
        task = null;
        List<Entry> all = new ArrayList<>();
        for (Map<Long, Entry> m : byWorld.values()) {
            for (Entry e : m.values()) {
                e.finish();
                all.add(e);
            }
        }
        result = Collections.unmodifiableList(all);
        scannedAt = System.currentTimeMillis();
        scanning = false;
        List<UUID> list = new ArrayList<>(waiters);
        waiters.clear();
        for (UUID id : list) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && p.hasPermission(clre20.tickBlade.TickBlade.PERM_HOTSPOTS)) {
                HotspotMenu.open(plugin, p, HotspotMenu.Mode.ENTITIES, 0);
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        scanning = false;
        waiters.clear();
        result = Collections.emptyList();
    }
}
