package clre20.tickBlade.ai;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Settings;
import clre20.tickBlade.util.Confinement;
import clre20.tickBlade.util.Protection;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.GameMode;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zoglin;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Witch;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.Material;
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
    /** 今晚為了去睡覺喚醒的紀錄 {第幾天, 開始的 tick}；只存 UUID，每輪掃描清掉兩天前的 */
    private final Map<UUID, long[]> sleepAttempt = new HashMap<>();

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
        sleepAttempt.values().removeIf(a -> tick - a[1] > 48000);
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

        Why inel = ineligible(mob, s);
        Why wake = inel == null ? wakeReason(mob, s, snap, true) : null;
        int crowd = snap.countSameType(i, s.crowdRadius);

        if (!paused) {
            if (inel != null || wake != null) return;
            if (crowd >= s.crowdPauseAt) {
                pause(mob, pdc, REASON_CROWD);
                curCrowd++;
                return;
            }
            if (s.aiConfined && Confinement.isConfined(mob) && confinedHold(mob, s, snap) == null) {
                pause(mob, pdc, REASON_CONFINED);
                curConfined++;
            }
            return;
        }

        // 已暫停：判斷是否恢復
        if (inel != null || wake != null) {
            resume(mob, pdc);
            return;
        }
        if (crowd > s.crowdResumeAt) {
            // 仍然擁擠（介於恢復與暫停門檻之間也維持暫停）
            countReason(crowd >= s.crowdPauseAt ? REASON_CROWD : reason, pdc);
            return;
        }
        if (s.aiConfined && Confinement.isConfined(mob) && confinedHold(mob, s, snap) == null) {
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

    /** 判斷原因：messages.yml 的 inspect.why.<key>，附帶變數 */
    public record Why(String key, Object... vars) {
    }

    /** 附近有沒有威脅／可攻擊目標；掃描用快照，inspect 用即時查詢 */
    interface Nearby {
        boolean threatWithin(double x, double y, double z, double radius);

        boolean targetWithin(double x, double y, double z, double radius);
    }

    /** 不能被暫停的原因（null = 可以暫停） */
    private Why ineligible(Mob mob, Settings s) {
        if (s.aiExcluded.contains(mob.getType())) return new Why("excluded-type");
        if (mob.isLeashed()) return new Why("leashed");
        if (s.aiSkipTamed && mob instanceof Tameable t && t.isTamed()) return new Why("tamed");
        if (mob instanceof Raider r && r.getRaid() != null) return new Why("raid");
        if (mob.getTarget() instanceof Player) return new Why("targeting-player");
        if (Protection.hasKeyword(mob, s)) return new Why("keyword", "keyword", s.nameKeyword);
        if (Protection.isForeign(mob, s)) return new Why("foreign");
        return null;
    }

    /** 這個時候需要 AI 的原因（null = 不需要），計算交給原版 */
    private Why wakeReason(Mob mob, Settings s, Nearby near, boolean record) {
        Long until = wakeUntil.get(mob.getUniqueId());
        long now = plugin.getServer().getCurrentTick();
        if (until != null && until > now) return new Why("woken", "seconds", (until - now + 19) / 20);
        if (s.wakeLoveMode && mob instanceof Animals a && a.isLoveMode()) return new Why("love");
        if (s.wakeShearedSheep && mob instanceof Sheep sh && sh.isSheared() && sh.isAdult()) return new Why("sheared");
        if (mob instanceof Villager v) {
            if (v.isTrading()) return new Why("trading");
            // 白天還躺在床上（例如半夜被暫停、沒有工作時段的傻子／無業村民）：喚醒讓它起床
            if (v.isSleeping()) {
                long t = mob.getWorld().getTime();
                if (t < 12000) return new Why("wake-up");
            }
            // 晚上：有床的村民喚醒讓它走到床上；躺下後可以再暫停，白天會被叫醒起床。
            // 每晚最多喚醒 villager-sleep-max-seconds 秒，走不到床（例如床上方空間不夠）就放棄，不整晚開著 AI
            if (v.isAdult() && !v.isSleeping() && v.getMemory(MemoryKey.HOME) != null) {
                Why sleep = sleepWake(v, s, record);
                if (sleep != null) return sleep;
            }
            // 附近有殭屍、掠奪者等威脅：要有 AI 才會逃跑（被關住的村民跑不掉，交給鐵傀儡農場規則判斷）
            if (s.villagerThreatRadius > 0
                    && near.threatWithin(mob.getX(), mob.getY(), mob.getZ(), s.villagerThreatRadius)
                    && !Confinement.isConfined(mob)) return new Why("threat", "radius", fmtR(s.villagerThreatRadius));
            if (v.isAdult()) {
                Villager.Profession p = v.getProfession();
                // 沒有職業、可以走動的村民要有 AI 才會去找工作站；被困住的可以暫停，
                // 附近放下工作站方塊時再喚醒（onWorkstationPlace）
                if (p == Villager.Profession.NONE) return Confinement.isConfined(mob) ? null : new Why("unemployed");
                if (p != Villager.Profession.NITWIT && !s.villagerWindows.isEmpty()) {
                    long time = mob.getWorld().getTime();
                    for (Settings.TimeWindow win : s.villagerWindows) {
                        if (win.contains(time)) return new Why("work-window", "from", win.from(), "to", win.to());
                    }
                }
            }
        }
        return null;
    }

    private Why sleepWake(Villager v, Settings s, boolean record) {
        World w = v.getWorld();
        long time = w.getTime();
        Settings.TimeWindow hit = null;
        for (Settings.TimeWindow win : s.villagerSleepWindows) {
            if (win.contains(time)) {
                hit = win;
                break;
            }
        }
        if (hit == null) return null;
        long now = plugin.getServer().getCurrentTick();
        long day = w.getFullTime() / 24000L;
        long[] a = sleepAttempt.get(v.getUniqueId());
        if (a == null || a[0] != day) {
            a = new long[]{day, now};
            if (record) sleepAttempt.put(v.getUniqueId(), a);
        }
        long left = s.villagerSleepMaxTicks - (now - a[1]);
        if (left <= 0) return null; // 今晚已經試過了，走不到床就放棄
        return new Why("sleep-window", "from", hit.from(), "to", hit.to(), "seconds", (left + 19) / 20);
    }

    /**
     * 被關住但仍要保持清醒的原因（null = 被關住就可以暫停）：
     * 敵對生物附近有可攻擊的目標（生存／冒險模式玩家、村民、鐵傀儡）才能攻擊；
     * 鐵傀儡農場的村民附近有威脅。
     */
    private static Why confinedHold(Mob mob, Settings s, Nearby near) {
        if (mob instanceof Enemy) {
            if (s.hostileTargetRadius <= 0) return new Why("hostile-no-limit");
            if (near.targetWithin(mob.getX(), mob.getY(), mob.getZ(), s.hostileTargetRadius)) {
                return new Why("hostile-target", "radius", fmtR(s.hostileTargetRadius));
            }
            return null;
        }
        if (mob instanceof Villager && s.golemFarmRadius > 0
                && near.threatWithin(mob.getX(), mob.getY(), mob.getZ(), s.golemFarmRadius)) {
            return new Why("golem-farm", "radius", fmtR(s.golemFarmRadius));
        }
        return null;
    }

    private static String fmtR(double r) {
        return r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    // ---------- /tickb inspect ----------

    /**
     * 即時診斷一隻生物（不改變狀態）。
     * state：disabled / other（別人關掉 AI）/ paused / awake
     * verdict：ineligible / wake / pause-crowd / keep-crowd / pause-confined / hold / normal
     */
    public record Diagnosis(String state, Byte pausedReason, int crowd, boolean confined, String verdict, Why why) {
    }

    public Diagnosis diagnose(Mob mob) {
        Settings s = plugin.settings();
        Byte reason = mob.getPersistentDataContainer().get(pausedKey, PersistentDataType.BYTE);
        int crowd = liveCrowd(mob, s.crowdRadius);
        boolean confined = Confinement.isConfined(mob);
        if (!s.aiEnabled || !s.aiWorld(mob.getWorld().getName())) {
            return new Diagnosis("disabled", reason, crowd, confined, "normal", null);
        }
        String state = reason != null && !mob.isAware() ? "paused" : !mob.isAware() ? "other" : "awake";
        if (state.equals("other")) return new Diagnosis(state, null, crowd, confined, "normal", null);
        Why inel = ineligible(mob, s);
        if (inel != null) return new Diagnosis(state, reason, crowd, confined, "ineligible", inel);
        Nearby live = new LiveNearby(mob.getWorld());
        Why wake = wakeReason(mob, s, live, false); // inspect 不改變狀態
        if (wake != null) return new Diagnosis(state, reason, crowd, confined, "wake", wake);
        if (crowd >= s.crowdPauseAt) return new Diagnosis(state, reason, crowd, confined, "pause-crowd", null);
        if (state.equals("paused") && crowd > s.crowdResumeAt) {
            return new Diagnosis(state, reason, crowd, confined, "keep-crowd", null);
        }
        if (s.aiConfined && confined) {
            Why hold = confinedHold(mob, s, live);
            if (hold != null) return new Diagnosis(state, reason, crowd, confined, "hold", hold);
            return new Diagnosis(state, reason, crowd, confined, "pause-confined", null);
        }
        return new Diagnosis(state, reason, crowd, confined, "normal", null);
    }

    private static int liveCrowd(Mob mob, double radius) {
        double r2 = radius * radius;
        int n = 0;
        for (Entity e : mob.getNearbyEntities(radius, radius, radius)) {
            if (e.getType() != mob.getType()) continue;
            double dx = e.getX() - mob.getX();
            double dy = e.getY() - mob.getY();
            double dz = e.getZ() - mob.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) n++;
        }
        return n + 1; // 含自己
    }

    /** inspect 用：即時查詢附近的實體 */
    private static final class LiveNearby implements Nearby {
        private final World world;

        LiveNearby(World world) {
            this.world = world;
        }

        @Override
        public boolean threatWithin(double x, double y, double z, double radius) {
            return any(x, y, z, radius, true);
        }

        @Override
        public boolean targetWithin(double x, double y, double z, double radius) {
            return any(x, y, z, radius, false);
        }

        private boolean any(double x, double y, double z, double radius, boolean threat) {
            double r2 = radius * radius;
            org.bukkit.Location loc = new org.bukkit.Location(world, x, y, z);
            for (Entity e : world.getNearbyEntities(loc, radius, radius, radius)) {
                if (!(e instanceof LivingEntity le)) continue;
                boolean match = threat ? WorldSnapshot.isVillagerThreat(le) : WorldSnapshot.isHostileTarget(le);
                if (!match) continue;
                double dx = e.getX() - x;
                double dy = e.getY() - y;
                double dz = e.getZ() - z;
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
            return false;
        }
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

    /** 右鍵點擊被暫停的村民時喚醒（沒職業的村民只會搖頭，不會開交易視窗） */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager v)) return;
        if (!v.getPersistentDataContainer().has(pausedKey)) return;
        wake(v, plugin.settings().wakeOnInteractTicks);
    }

    /** 放下工作站方塊時，附近被暫停的無職業村民喚醒一段時間去認領 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorkstationPlace(BlockPlaceEvent e) {
        if (!WORKSTATIONS.contains(e.getBlockPlaced().getType())) return;
        Settings s = plugin.settings();
        if (!s.aiEnabled || s.wakeWorkstationTicks <= 0) return;
        for (Villager v : e.getBlockPlaced().getLocation().toCenterLocation()
                .getNearbyEntitiesByType(Villager.class, s.workstationWakeRadius)) {
            if (v.getProfession() == Villager.Profession.NONE && v.getPersistentDataContainer().has(pausedKey)) {
                wake(v, s.wakeWorkstationTicks);
            }
        }
    }

    private static final java.util.Set<Material> WORKSTATIONS = java.util.EnumSet.of(
            Material.BLAST_FURNACE, Material.SMOKER, Material.CARTOGRAPHY_TABLE, Material.BREWING_STAND,
            Material.COMPOSTER, Material.BARREL, Material.FLETCHING_TABLE, Material.CAULDRON,
            Material.WATER_CAULDRON, Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON,
            Material.LECTERN, Material.STONECUTTER, Material.LOOM, Material.SMITHING_TABLE, Material.GRINDSTONE);

    /** 被任何生物（含投射物）攻擊時喚醒，才能反擊或逃跑 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Mob mob)) return;
        if (!mob.getPersistentDataContainer().has(pausedKey) && !wakeUntil.containsKey(mob.getUniqueId())) return;
        wake(mob, plugin.settings().wakeWhenDamagedTicks);
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
    static final class WorldSnapshot implements Nearby {
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
        /** 敵對生物可以攻擊的目標：生存／冒險模式玩家、村民、鐵傀儡 */
        final double[] tx;
        final double[] ty;
        final double[] tz;

        private WorldSnapshot(World world, int n, int zn, int tn, double cell) {
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
            this.tx = new double[tn];
            this.ty = new double[tn];
            this.tz = new double[tn];
        }

        static WorldSnapshot take(World w, double radius) {
            List<Mob> mobs = new ArrayList<>(w.getEntitiesByClass(Mob.class));
            int zn = 0;
            int tn = 0;
            for (Mob m : mobs) {
                if (isVillagerThreat(m)) zn++;
                if (isHostileTarget(m)) tn++;
            }
            List<Player> players = new ArrayList<>();
            for (Player p : w.getPlayers()) {
                GameMode gm = p.getGameMode();
                if (!p.isDead() && (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE)) players.add(p);
            }
            tn += players.size();
            WorldSnapshot s = new WorldSnapshot(w, mobs.size(), zn, tn, Math.max(1.0, radius));
            int zi = 0;
            int ti = 0;
            for (Player p : players) {
                s.tx[ti] = p.getX();
                s.ty[ti] = p.getY();
                s.tz[ti] = p.getZ();
                ti++;
            }
            for (int i = 0; i < mobs.size(); i++) {
                Mob m = mobs.get(i);
                s.ids[i] = m.getUniqueId();
                s.x[i] = m.getX();
                s.y[i] = m.getY();
                s.z[i] = m.getZ();
                s.type[i] = m.getType().ordinal();
                s.add(i);
                if (isHostileTarget(m)) {
                    s.tx[ti] = s.x[i];
                    s.ty[ti] = s.y[i];
                    s.tz[ti] = s.z[i];
                    ti++;
                }
                if (isVillagerThreat(m)) {
                    s.zx[zi] = s.x[i];
                    s.zy[zi] = s.y[i];
                    s.zz[zi] = s.z[i];
                    zi++;
                }
            }
            return s;
        }

        static boolean isHostileTarget(LivingEntity m) {
            if (m instanceof Player p) {
                GameMode gm = p.getGameMode();
                return !p.isDead() && (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE);
            }
            return m instanceof AbstractVillager || m instanceof IronGolem;
        }

        @Override
        public boolean targetWithin(double px, double py, double pz, double radius) {
            double r2 = radius * radius;
            for (int k = 0; k < tx.length; k++) {
                double dx = tx[k] - px;
                double dy = ty[k] - py;
                double dz = tz[k] - pz;
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
            return false;
        }

        /** 村民會害怕的生物：殭屍類（含屍殼、沉屍、殭屍村民）、豬屍獸、掠奪者類（女巫除外）、惱鬼 */
        static boolean isVillagerThreat(LivingEntity m) {
            if (m instanceof Zombie || m instanceof Zoglin || m instanceof Vex) return true;
            return m instanceof Raider && !(m instanceof Witch);
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

        @Override
        public boolean threatWithin(double px, double py, double pz, double radius) {
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
