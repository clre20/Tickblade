package clre20.tickBlade.shed;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Settings;
import clre20.tickBlade.monitor.TickMonitor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * C. 自動降載：依最近平均 MSPT 分級降低每位玩家的模擬距離／視距，恢復後一級一級還原。
 * 每秒判斷一次，只讀取 TickMonitor 的陣列。
 */
public final class LoadShedder implements Listener {

    private final TickBlade plugin;
    /** 降載前的原始距離 {視距, 模擬距離}，只存 UUID */
    private final Map<UUID, int[]> originals = new HashMap<>();
    private BukkitTask task;
    private int level;
    private long lastChange;
    private long belowSince;

    public LoadShedder(TickBlade plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        Settings s = plugin.settings();
        if (!s.shedEnabled || s.shedLevels.isEmpty()) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::evaluate, 20L, 20L);
    }

    /** 停止並還原所有玩家 */
    public void stop() {
        if (task != null) task.cancel();
        task = null;
        if (level != 0) {
            level = 0;
            applyAll();
        }
        originals.clear();
        belowSince = 0;
    }

    private void evaluate() {
        Settings s = plugin.settings();
        TickMonitor mon = plugin.tickMonitor();
        int window = s.shedAverageSeconds * 20;
        if (mon.samples() < window) return;
        double avg = mon.average(window);
        long now = System.currentTimeMillis();
        boolean canChange = now - lastChange >= s.shedMinSecondsBetween * 1000L;

        int target = 0;
        for (int i = 0; i < s.shedLevels.size(); i++) {
            if (avg >= s.shedLevels.get(i).mspt()) target = i + 1;
        }
        if (level > s.shedLevels.size()) level = s.shedLevels.size();

        if (target > level) {
            belowSince = 0;
            if (canChange) change(level + 1, avg);
            return;
        }
        if (level > 0 && avg < s.shedRecoverBelow) {
            if (belowSince == 0) belowSince = now;
            if (now - belowSince >= s.shedRecoverAfterSeconds * 1000L && canChange) {
                change(level - 1, avg);
                belowSince = now; // 下一級還原要再等一段時間
            }
        } else {
            belowSince = 0;
        }
    }

    private void change(int to, double avg) {
        int from = level;
        level = to;
        lastChange = System.currentTimeMillis();
        applyAll();
        Settings s = plugin.settings();
        String mspt = TickMonitor.fmt(avg);
        if (to > from) {
            int[] d = effective(s, to);
            String unchanged = plugin.messages().raw("shedding.unchanged");
            String sim = d[1] > 0 ? String.valueOf(d[1]) : unchanged;
            String view = d[0] > 0 ? String.valueOf(d[0]) : unchanged;
            plugin.getLogger().info(plugin.messages().console("console.shedding-raised", "from", from, "to", to, "mspt", mspt));
            if (s.shedNotify) plugin.notifyAdmins("shedding.raised", "mspt", mspt, "from", from, "to", to, "sim", sim, "view", view);
        } else {
            plugin.getLogger().info(plugin.messages().console("console.shedding-lowered", "from", from, "to", to, "mspt", mspt));
            if (s.shedNotify) plugin.notifyAdmins("shedding.lowered", "mspt", mspt, "from", from, "to", to);
        }
    }

    /** 等級 L 的實際距離 {視距, 模擬距離}：取 1..L 中設定值的最小值，0 = 不變 */
    private static int[] effective(Settings s, int lvl) {
        int view = 0;
        int sim = 0;
        for (int i = 0; i < lvl && i < s.shedLevels.size(); i++) {
            Settings.ShedLevel l = s.shedLevels.get(i);
            if (l.viewDistance() > 0) view = view == 0 ? l.viewDistance() : Math.min(view, l.viewDistance());
            if (l.simulationDistance() > 0) sim = sim == 0 ? l.simulationDistance() : Math.min(sim, l.simulationDistance());
        }
        return new int[]{view, sim};
    }

    private void applyAll() {
        for (Player p : plugin.getServer().getOnlinePlayers()) apply(p);
        if (level == 0) originals.clear();
    }

    private void apply(Player p) {
        if (level == 0) {
            int[] orig = originals.get(p.getUniqueId());
            if (orig == null) return;
            if (p.getViewDistance() != orig[0]) p.setViewDistance(orig[0]);
            if (p.getSimulationDistance() != orig[1]) p.setSimulationDistance(orig[1]);
            return;
        }
        int[] orig = originals.computeIfAbsent(p.getUniqueId(),
                id -> new int[]{p.getViewDistance(), p.getSimulationDistance()});
        int[] d = effective(plugin.settings(), level);
        int view = d[0] > 0 ? Math.min(orig[0], d[0]) : orig[0];
        int sim = d[1] > 0 ? Math.min(orig[1], d[1]) : orig[1];
        if (p.getViewDistance() != view) p.setViewDistance(view);
        if (p.getSimulationDistance() != sim) p.setSimulationDistance(sim);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (level == 0) return;
        UUID id = e.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && level > 0) apply(p);
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        originals.remove(e.getPlayer().getUniqueId());
    }

    public int level() {
        return level;
    }

    /** 目前等級的 {視距, 模擬距離}（0 = 不變） */
    public int[] currentDistances() {
        return effective(plugin.settings(), level);
    }
}
