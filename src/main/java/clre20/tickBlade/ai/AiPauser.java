package clre20.tickBlade.ai;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Settings;
import clre20.tickBlade.util.Confinement;
import clre20.tickBlade.util.Protection;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zoglin;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * D. 擠在一起或被關住的生物暫停 AI（Mob#setAware(false)）。
 * 需要 AI 的時候短暫喚醒，讓原版自己計算（補貨、升級、長羊毛、繁殖）。
 * 暫停狀態以 PDC 標記存在生物身上，關閉功能或移除插件前可完整恢復。
 */
public final class AiPauser implements Listener {

    public static final byte REASON_CROWD = 1;
    public static final byte REASON_CONFINED = 2;

    private final TickBlade plugin;
    private final NamespacedKey pausedKey;
    /** 暫時喚醒到第幾個 tick（只存 UUID） */
    private final Map<UUID, Long> wakeUntil = new HashMap<>();

    private BukkitTask scanTask;
    private BukkitTask workTask;
    private final List<WorldSnapshot> pending = new ArrayList<>();
    private int worldIdx;
    private int entityIdx;
    private long tick;

    // 統計（上一輪完整掃描的結果）
    private int pausedCrowd;
    private int pausedConfined;
    private int curCrowd;
    private int curConfined;

    public AiPauser(TickBlade plugin) {
        this.plugin = plugin;
        this.pausedKey = new NamespacedKey(plugin, "ai-paused");
    }

    public void start() {
        stopTasks();
        Settings s = plugin.settings();
        if (!s.aiEnabled) {
            restoreAll();
            return;
        }
        // 世界設定改變時，不在套用範圍的世界先恢復
        for (World w : plugin.getServer().getWorlds()) {
            if (!s.aiWorld(w.getName())) restoreWorld(w);
        }
        long period = s.aiScanIntervalSeconds * 20L;
        scanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::beginScan, 40L, period);
        workTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::work, 41L, 1L);
    }

    public void stopTasks() {
        if (scanTask != null) scanTask.cancel();
        if (workTask != null) workTask.cancel();
        scanTask = null;
        workTask = null;
        pending.clear();
    }

    /** 關服或停用：恢復所有已載入的被暫停生物 */
    public void shutdown() {
        stopTasks();
        restoreAll();
        wakeUntil.clear();
    }

    // ---------- 掃描 ----------

    private void beginScan() {
        tick = plugin.getServer().getCurrentTick();
        if (!pending.isEmpty()) return; // 上一輪還沒處理完
        // 清掉過期的喚醒紀錄
        wakeUntil.values().removeIf(t -> t <= tick);
        Settings s = plugin.settings();
        for (World w : plugin.getServer().getWorlds()) {
            if (!s.aiWorld(w.getName())) continue;
            WorldSnapshot snap = WorldSnapshot.take(w, s.crowdRadius);
            if (snap.size() > 0) pending.add(snap);
        }
        worldIdx = 0;
        entityIdx = 0;
        curCrowd = 0;
        curConfined = 0;
        if (pending.isEmpty()) finishScan();
    }

    private void work() {
        if (pending.isEmpty()) return;
        tick = plugin.getServer().getCurrentTick();
        Settings s = plugin.settings();
        long deadline = System.nanoTime() + (long) (s.aiMaxMsPerTick * 1_000_000L);
        while (worldIdx < pending.size()) {
            WorldSnapshot snap = pending.get(worldIdx);
            while (entityIdx < snap.size()) {
                process(snap, entityIdx++, s);
                if (System.nanoTime() > deadline) return;
            }
            worldIdx++;
            entityIdx = 0;
        }
        finishScan();
    }

    private void finishScan() {
        pending.clear();
        pausedCrowd = curCrowd;
        pausedConfined = curConfined;
    }

    private void process(WorldSnapshot snap, int i, Settings s) {
        Entity en = plugin.getServer().getEntity(snap.ids[i]);
        if (!(en instanceof Mob mob) || !mob.isValid() || mob.getWorld() != snap.world) return;

        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        Byte reason = pdc.get(pausedKey, PersistentDataType.BYTE);
        boolean paused = reason != null;
        if (paused && mob.isAware()) {
            // 被其他插件或指令恢復了：尊重它，移除標記
            pdc.remove(pausedKey);
            paused = false;
        }
        if (!paused && !mob.isAware()) return; // 別人關掉的 AI，不碰

        boolean eligible = eligible(mob, s);
        boolean wake = eligible && needsAi(mob, s);
        int crowd = snap.countSameType(i, s.crowdRadius);

        if (!paused) {
            if (!eligible || wake) return;
            if (crowd >= s.crowdPauseAt) {
                pause(mob, pdc, REASON_CROWD);
                curCrowd++;
                return;
            }
            if (s.aiConfined && Confinement.isConfined(mob) && !golemFarm(mob, snap, s)) {
                pause(mob, pdc, REASON_CONFINED);
                curConfined++;
            }
            return;
        }

        // 已暫停：判斷是否恢復
        if (!eligible || wake) {
            resume(mob, pdc);
            return;
        }
        if (crowd > s.crowdResumeAt) {
            // 仍然擁擠（介於恢復與暫停門檻之間也維持暫停）
            countReason(crowd >= s.crowdPauseAt ? REASON_CROWD : reason, pdc);
            return;
        }
        if (s.aiConfined && Confinement.isConfined(mob) && !golemFarm(mob, snap, s)) {
            countReason(REASON_CONFINED, pdc);
            return;
        }
        resume(mob, pdc);
    }

    private void countReason(byte reason, PersistentDataContainer pdc) {
        Byte old = pdc.get(pausedKey, PersistentDataType.BYTE);
        if (old == null || old != reason) pdc.set(pausedKey, PersistentDataType.BYTE, reason);
        if (reason == REASON_CROWD) curCrowd++;
        else curConfined++;
    }

    /** 可以被暫停的生物 */
    private boolean eligible(Mob mob, Settings s) {
        if (s.aiExcluded.contains(mob.getType())) return false;
        if (mob instanceof Player) return false;
        if (mob.isLeashed()) return false;
        if (s.aiSkipTamed && mob instanceof Tameable t && t.isTamed()) return false;
        if (mob instanceof Raider r && r.getRaid() != null) return false;
        if (mob.getTarget() instanceof Player) return false;
        if (Protection.hasKeyword(mob, s)) return false;
        return !Protection.isForeign(mob, s);
    }

    /** 這個時候需要 AI，交給原版計算 */
    private boolean needsAi(Mob mob, Settings s) {
        Long until = wakeUntil.get(mob.getUniqueId());
        if (until != null && until > tick) return true;
        if (s.wakeLoveMode && mob instanceof Animals a && a.isLoveMode()) return true;
        if (s.wakeShearedSheep && mob instanceof Sheep sh && sh.isSheared() && sh.isAdult()) return true;
        if (mob instanceof Villager v) {
            if (v.isTrading() || v.isSleeping()) return true;
            if (v.isAdult()) {
                Villager.Profession p = v.getProfession();
                // 沒有職業的村民要有 AI 才能找到工作站方塊（交易所剛放好工作站時）
                if (p == Villager.Profession.NONE) return true;
                if (p != Villager.Profession.NITWIT && !s.villagerWindows.isEmpty()) {
                    long time = mob.getWorld().getTime();
                    for (Settings.TimeWindow win : s.villagerWindows) {
                        if (win.contains(time)) return true;
                    }
                }
            }
        }
        return false;
    }

    /** 鐵傀儡農場：被關住的村民附近有殭屍類生物 */
    private boolean golemFarm(Mob mob, WorldSnapshot snap, Settings s) {
        if (!(mob instanceof Villager) || s.golemFarmRadius <= 0) return false;
        return snap.zombieWithin(mob.getX(), mob.getY(), mob.getZ(), s.golemFarmRadius);
    }

    private void pause(Mob mob, PersistentDataContainer pdc, byte reason) {
        pdc.set(pausedKey, PersistentDataType.BYTE, reason);
        mob.setAware(false);
    }

    private void resume(Mob mob, PersistentDataContainer pdc) {
        pdc.remove(pausedKey);
        mob.setAware(true);
    }

    public boolean isPausedByUs(Entity e) {
        return e.getPersistentDataContainer().has(pausedKey);
    }

    // ---------- 喚醒 ----------

    public void wake(Entity e, int ticks) {
        if (ticks <= 0 || !(e instanceof Mob mob)) return;
        long now = plugin.getServer().getCurrentTick();
        wakeUntil.merge(e.getUniqueId(), now + ticks, Math::max);
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (pdc.has(pausedKey)) resume(mob, pdc);
    }

    /** 交易後喚醒，讓村民升級 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTradeClose(InventoryCloseEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory inv)) return;
        if (!(inv.getMerchant() instanceof Villager v)) return;
        if (!plugin.settings().aiEnabled) return;
        wake(v, plugin.settings().wakeAfterTradeTicks);
    }

    /** 被玩家攻擊時喚醒 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Mob mob)) return;
        if (!mob.getPersistentDataContainer().has(pausedKey) && !wakeUntil.containsKey(mob.getUniqueId())) return;
        Entity d = e.getDamager();
        boolean byPlayer = d instanceof Player || (d instanceof Projectile p && p.getShooter() instanceof Player);
        if (byPlayer) wake(mob, plugin.settings().wakeWhenDamagedTicks);
    }

    /** 功能關閉或世界不套用時，載入的區塊裡有暫停標記的就恢復 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Settings s = plugin.settings();
        if (s.aiEnabled && s.aiWorld(e.getWorld().getName())) return;
        for (Entity en : e.getEntities()) {
            if (en instanceof Mob mob && mob.getPersistentDataContainer().has(pausedKey)) {
                resume(mob, mob.getPersistentDataContainer());
            }
        }
    }

    // ---------- 恢復 ----------

    public int restoreAll() {
        int n = 0;
        for (World w : plugin.getServer().getWorlds()) n += restoreWorld(w);
        pausedCrowd = 0;
        pausedConfined = 0;
        return n;
    }

    private int restoreWorld(World w) {
        int n = 0;
        for (Mob mob : w.getEntitiesByClass(Mob.class)) {
            PersistentDataContainer pdc = mob.getPersistentDataContainer();
            if (pdc.has(pausedKey)) {
                resume(mob, pdc);
                n++;
            }
        }
        return n;
    }

    public int pausedCrowd() {
        return pausedCrowd;
    }

    public int pausedConfined() {
        return pausedConfined;
    }

    public int woken() {
        long now = plugin.getServer().getCurrentTick();
        int n = 0;
        for (Iterator<Long> it = wakeUntil.values().iterator(); it.hasNext(); ) {
            if (it.next() > now) n++;
        }
        return n;
    }

    // ---------- 快照 ----------

    /** 一個世界的生物位置快照；只存 UUID 與座標，不持有實體 */
    static final class WorldSnapshot {
        final World world; // 只在這一輪掃描期間持有
        final UUID[] ids;
        final double[] x;
        final double[] y;
        final double[] z;
        final int[] type;
        final double cell;
        final Map<Long, int[]> grid = new HashMap<>();
        final double[] zx;
        final double[] zy;
        final double[] zz;

        private WorldSnapshot(World world, int n, int zn, double cell) {
            this.world = world;
            this.ids = new UUID[n];
            this.x = new double[n];
            this.y = new double[n];
            this.z = new double[n];
            this.type = new int[n];
            this.cell = cell;
            this.zx = new double[zn];
            this.zy = new double[zn];
            this.zz = new double[zn];
        }

        static WorldSnapshot take(World w, double radius) {
            List<Mob> mobs = new ArrayList<>(w.getEntitiesByClass(Mob.class));
            int zn = 0;
            for (Mob m : mobs) if (isZombieType(m)) zn++;
            WorldSnapshot s = new WorldSnapshot(w, mobs.size(), zn, Math.max(1.0, radius));
            int zi = 0;
            for (int i = 0; i < mobs.size(); i++) {
                Mob m = mobs.get(i);
                s.ids[i] = m.getUniqueId();
                s.x[i] = m.getX();
                s.y[i] = m.getY();
                s.z[i] = m.getZ();
                s.type[i] = m.getType().ordinal();
                s.add(i);
                if (isZombieType(m)) {
                    s.zx[zi] = s.x[i];
                    s.zy[zi] = s.y[i];
                    s.zz[zi] = s.z[i];
                    zi++;
                }
            }
            return s;
        }

        private static boolean isZombieType(LivingEntity m) {
            return m instanceof Zombie || m instanceof Zoglin || m.getType() == EntityType.ZOMBIFIED_PIGLIN;
        }

        int size() {
            return ids.length;
        }

        private long key(int cx, int cy, int cz) {
            return ((long) (cx & 0x1FFFFF) << 42) | ((long) (cy & 0x1FFFFF) << 21) | (cz & 0x1FFFFF);
        }

        private void add(int i) {
            long k = key(c(x[i]), c(y[i]), c(z[i]));
            int[] list = grid.get(k);
            if (list == null) {
                list = new int[5];
                grid.put(k, list);
            } else if (list[0] + 1 >= list.length) {
                list = java.util.Arrays.copyOf(list, list.length * 2);
                grid.put(k, list);
            }
            list[++list[0]] = i; // list[0] 是數量
        }

        private int c(double v) {
            return (int) Math.floor(v / cell);
        }

        /** 半徑內同種類的生物數量（含自己） */
        int countSameType(int i, double radius) {
            double r2 = radius * radius;
            int cx = c(x[i]);
            int cy = c(y[i]);
            int cz = c(z[i]);
            int n = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int[] list = grid.get(key(cx + dx, cy + dy, cz + dz));
                        if (list == null) continue;
                        for (int k = 1; k <= list[0]; k++) {
                            int j = list[k];
                            if (type[j] != type[i]) continue;
                            double ddx = x[j] - x[i];
                            double ddy = y[j] - y[i];
                            double ddz = z[j] - z[i];
                            if (ddx * ddx + ddy * ddy + ddz * ddz <= r2) n++;
                        }
                    }
                }
            }
            return n;
        }

        boolean zombieWithin(double px, double py, double pz, double radius) {
            double r2 = radius * radius;
            for (int k = 0; k < zx.length; k++) {
                double dx = zx[k] - px;
                double dy = zy[k] - py;
                double dz = zz[k] - pz;
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
            return false;
        }
    }
}
