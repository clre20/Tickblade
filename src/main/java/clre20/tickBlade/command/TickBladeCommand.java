package clre20.tickBlade.command;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Messages;
import clre20.tickBlade.config.Settings;
import clre20.tickBlade.monitor.TickMonitor;
import clre20.tickBlade.spark.SparkCapture;
import clre20.tickBlade.update.UpdateChecker;
import clre20.tickBlade.util.OpLevel;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /tickblade（別名 /tb） */
public final class TickBladeCommand implements TabExecutor {

    /** 子指令 → {權限, 用法, 說明鍵} */
    private static final Map<String, String[]> SUBS = new LinkedHashMap<>();

    static {
        SUBS.put("status", new String[]{TickBlade.PERM_STATUS, "status", "help.entries.status"});
        SUBS.put("hotspots", new String[]{TickBlade.PERM_HOTSPOTS, "hotspots", "help.entries.hotspots"});
        SUBS.put("inspect", new String[]{TickBlade.PERM_INSPECT, "inspect", "help.entries.inspect"});
        SUBS.put("reports", new String[]{TickBlade.PERM_REPORTS, "reports", "help.entries.reports"});
        SUBS.put("profile", new String[]{TickBlade.PERM_PROFILE, "profile", "help.entries.profile"});
        SUBS.put("cleanup", new String[]{TickBlade.PERM_CLEANUP, "cleanup", "help.entries.cleanup"});
        SUBS.put("ai", new String[]{TickBlade.PERM_AI, "ai restore", "help.entries.ai-restore"});
        SUBS.put("reload", new String[]{TickBlade.PERM_RELOAD, "reload", "help.entries.reload"});
        SUBS.put("version", new String[]{TickBlade.PERM_VERSION, "version", "help.entries.version"});
    }

    private final TickBlade plugin;

    public TickBladeCommand(TickBlade plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        Messages m = plugin.messages();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) {
            if (!sender.hasPermission(TickBlade.PERM_HELP)) {
                m.send(sender, "common.no-permission");
                return true;
            }
            help(sender, label);
            return true;
        }
        String[] info = SUBS.get(sub);
        if (info == null) {
            m.send(sender, "common.unknown-subcommand", "label", label);
            return true;
        }
        if (!sender.hasPermission(info[0])) {
            m.send(sender, "common.no-permission");
            return true;
        }
        switch (sub) {
            case "status" -> status(sender);
            case "hotspots" -> {
                if (!(sender instanceof Player p)) {
                    m.send(sender, "common.player-only");
                    return true;
                }
                int need = plugin.settings().hotspotMinOpLevel;
                if (!OpLevel.atLeast(p, need)) {
                    m.send(sender, "common.op-level-required", "level", need);
                    return true;
                }
                plugin.hotspots().open(p, false);
            }
            case "inspect" -> {
                if (!(sender instanceof Player p)) {
                    m.send(sender, "common.player-only");
                    return true;
                }
                int need = plugin.settings().inspectMinOpLevel;
                if (!OpLevel.atLeast(p, need)) {
                    m.send(sender, "common.op-level-required", "level", need);
                    return true;
                }
                Inspector.inspect(plugin, p);
            }
            case "reports" -> reports(sender);
            case "profile" -> plugin.sparkCapture().startManual(sender);
            case "cleanup" -> m.send(sender, "cleanup.queued", "count", plugin.despawnFixer().sweep());
            case "ai" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("restore")) {
                    m.send(sender, "ai.usage", "label", label);
                    return true;
                }
                int n = plugin.aiPauser().restoreAll();
                m.send(sender, "ai.restored", "count", n);
                plugin.getLogger().info(m.console("console.ai-restored", "count", n));
            }
            case "reload" -> {
                String err = plugin.reload();
                if (err == null) plugin.messages().send(sender, "common.reload-done");
                else plugin.messages().send(sender, "common.reload-failed", "error", err);
            }
            case "version" -> version(sender);
            default -> m.send(sender, "common.unknown-subcommand", "label", label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        Messages m = plugin.messages();
        m.sendPlain(sender, "help.header", "version", plugin.getPluginMeta().getVersion());
        for (Map.Entry<String, String[]> e : SUBS.entrySet()) {
            String[] info = e.getValue();
            if (!canUse(sender, e.getKey(), info[0])) continue;
            m.sendPlain(sender, "help.line", "label", label, "usage", info[1], "description", m.raw(info[2]));
        }
    }

    private void status(CommandSender sender) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        TickMonitor mon = plugin.tickMonitor();
        m.sendPlain(sender, "status.header");
        int sec = s.shedAverageSeconds;
        double avg = mon.average(sec * 20);
        String color = avg >= 50 ? "&c" : avg >= 40 ? "&e" : "&a";
        m.sendPlain(sender, "status.mspt", "seconds", sec, "color", color, "mspt", TickMonitor.fmt(avg),
                "last", TickMonitor.fmt(mon.lastTick()));

        if (!s.shedEnabled) m.sendPlain(sender, "status.shedding-off");
        else if (plugin.loadShedder().level() == 0) m.sendPlain(sender, "status.shedding-none");
        else {
            int[] d = plugin.loadShedder().currentDistances();
            String unchanged = m.raw("shedding.unchanged");
            m.sendPlain(sender, "status.shedding", "level", plugin.loadShedder().level(), "max", s.shedLevels.size(),
                    "sim", d[1] > 0 ? d[1] : unchanged, "view", d[0] > 0 ? d[0] : unchanged);
        }

        if (!s.aiEnabled) m.sendPlain(sender, "status.ai-off");
        else {
            var ai = plugin.aiPauser();
            m.sendPlain(sender, "status.ai", "paused", ai.pausedCrowd() + ai.pausedConfined(),
                    "crowd", ai.pausedCrowd(), "confined", ai.pausedConfined(), "woken", ai.woken());
        }

        if (!s.despawnEnabled) m.sendPlain(sender, "status.despawn-off");
        else {
            var d = plugin.despawnFixer();
            m.sendPlain(sender, "status.despawn", "fixed", d.fixed(), "blocks", d.droppedBlocks(),
                    "items", d.droppedItems(), "blocked", d.blockedPickups());
        }

        String last = mon.lastSpikeAt() == 0 ? m.raw("status.spikes-none")
                : new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(mon.lastSpikeAt()))
                + " (" + TickMonitor.fmt(mon.lastSpikeMs()) + " ms)";
        m.sendPlain(sender, "status.spikes", "threshold", (int) s.spikeThresholdMs, "total", mon.totalSpikes(),
                "max", TickMonitor.fmt(mon.maxSpike()), "last", last);

        if (!s.stackEnabled) m.sendPlain(sender, "status.stacks-off");
        else {
            var sc = plugin.stackCapture();
            if (sc.totalCaptures() == 0) m.sendPlain(sender, "status.stacks-none");
            else m.sendPlain(sender, "status.stacks", "count", sc.totalCaptures(),
                    "time", new SimpleDateFormat("HH:mm:ss").format(new Date(sc.lastWhereAt())),
                    "where", String.valueOf(sc.lastWhere()).replace('&', '＆'));
        }

        SparkCapture cap = plugin.sparkCapture();
        if (cap.running()) m.sendPlain(sender, "status.profile-running", "seconds", cap.remainingSeconds());
        else {
            int count;
            try {
                count = SparkCapture.listReports(plugin.reportsDir()).size();
            } catch (IOException e) {
                count = 0;
            }
            m.sendPlain(sender, "status.profile-idle", "count", count);
        }
    }

    private void reports(CommandSender sender) {
        Messages m = plugin.messages();
        // 讀取資料夾放到背景執行緒
        plugin.io().execute(() -> {
            List<Path> files;
            try {
                files = SparkCapture.listReports(plugin.reportsDir());
            } catch (IOException e) {
                files = new ArrayList<>();
            }
            files.sort(Comparator.comparingLong(SparkCapture::modified).reversed());
            long total = 0;
            List<String[]> rows = new ArrayList<>();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            for (Path p : files) {
                long size = SparkCapture.size(p);
                total += size;
                if (rows.size() < 30) {
                    rows.add(new String[]{p.getFileName().toString(), SparkCapture.formatSize(size),
                            fmt.format(new Date(SparkCapture.modified(p)))});
                }
            }
            int count = files.size();
            long totalSize = total;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (count == 0) {
                    m.send(sender, "reports.empty");
                    return;
                }
                m.sendPlain(sender, "reports.header", "count", count, "size", SparkCapture.formatSize(totalSize));
                for (String[] r : rows) m.sendPlain(sender, "reports.line", "file", r[0], "size", r[1], "date", r[2]);
                m.sendPlain(sender, "reports.footer");
            });
        });
    }

    private void version(CommandSender sender) {
        Messages m = plugin.messages();
        UpdateChecker uc = plugin.updateChecker();
        m.send(sender, "version.current", "version", uc.currentVersion());
        UpdateChecker.Result cached = uc.cached();
        if (cached == null) m.send(sender, "version.checking");
        uc.check().thenAccept(r -> plugin.getServer().getScheduler().runTask(plugin, () -> sendResult(sender, r)));
    }

    public static void sendResultTo(TickBlade plugin, CommandSender sender, UpdateChecker.Result r) {
        Messages m = plugin.messages();
        String v = plugin.updateChecker().currentVersion();
        switch (r.state()) {
            case NEWER -> m.send(sender, "version.newer", "latest", r.latest(), "version", v, "url", r.url());
            case LATEST -> m.send(sender, "version.latest");
            case DEV -> m.send(sender, "version.dev", "latest", r.latest());
            case FAILED -> m.send(sender, "version.failed", "reason", m.raw(r.reasonKey(), "code", r.code()));
        }
    }

    private void sendResult(CommandSender sender, UpdateChecker.Result r) {
        if (sender instanceof Player p && !p.isOnline()) return;
        sendResultTo(plugin, sender, r);
    }

    /** 權限＋（熱點介面）OP 等級；主控台不能開介面，所以不列出 hotspots */
    private boolean canUse(CommandSender sender, String sub, String perm) {
        if (!sender.hasPermission(perm)) return false;
        int need;
        if (sub.equals("hotspots")) need = plugin.settings().hotspotMinOpLevel;
        else if (sub.equals("inspect")) need = plugin.settings().inspectMinOpLevel;
        else return true;
        // 這兩個只有玩家能用，主控台不列出
        return sender instanceof Player p && OpLevel.atLeast(p, need);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if (sender.hasPermission(TickBlade.PERM_HELP) && "help".startsWith(prefix)) out.add("help");
            for (Map.Entry<String, String[]> e : SUBS.entrySet()) {
                if (e.getKey().startsWith(prefix) && canUse(sender, e.getKey(), e.getValue()[0])) out.add(e.getKey());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ai") && sender.hasPermission(TickBlade.PERM_AI)) {
            if ("restore".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("restore");
        }
        return out;
    }
}
