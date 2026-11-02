package clre20.tickBlade.spark;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 透過 spark 指令錄製只含長 tick 的報告，並用 --save-to-file 存成檔案，
 * 錄完後搬到 plugins/TickBlade/reports/（網址會過期，所以不上傳）。
 */
public final class SparkCapture {

    private final TickBlade plugin;
    private volatile boolean running;
    private volatile long startedAt;
    private volatile int durationSeconds;
    private volatile boolean auto;
    private volatile UUID requester; // 手動錄製的玩家（null = 主控台或自動）
    private long lastAutoStart;
    private BukkitTask watchdog;

    public SparkCapture(TickBlade plugin) {
        this.plugin = plugin;
    }

    public boolean running() {
        return running;
    }

    public long remainingSeconds() {
        if (!running) return 0;
        return Math.max(0, durationSeconds - (System.currentTimeMillis() - startedAt) / 1000);
    }

    /** 卡頓次數達到門檻時呼叫（主執行緒）。回傳是否真的開始錄製 */
    public boolean tryAutoStart() {
        if (running) return false;
        Settings s = plugin.settings();
        long cooldownMs = s.profileCooldownMinutes * 60_000L;
        if (lastAutoStart != 0 && System.currentTimeMillis() - lastAutoStart < cooldownMs) return false;
        if (!start(true, null)) return false;
        lastAutoStart = System.currentTimeMillis();
        return true;
    }

    /** 手動錄製（/tb profile） */
    public void startManual(CommandSender sender) {
        if (running) {
            plugin.messages().send(sender, "spike.profile-already");
            return;
        }
        UUID id = sender instanceof org.bukkit.entity.Player p ? p.getUniqueId() : null;
        if (!start(false, id)) {
            boolean hasSpark = plugin.getServer().getCommandMap().getCommand("spark") != null;
            plugin.messages().send(sender, hasSpark ? "spike.profile-busy" : "spike.no-spark");
        }
    }

    private boolean start(boolean auto, UUID requester) {
        if (plugin.getServer().getCommandMap().getCommand("spark") == null) {
            plugin.getLogger().warning(plugin.messages().console("spike.no-spark"));
            return false;
        }
        Settings s = plugin.settings();
        this.running = true;
        this.auto = auto;
        this.requester = requester;
        this.startedAt = System.currentTimeMillis();
        this.durationSeconds = s.profileDurationSeconds;

        CommandSender feedback = plugin.getServer().createCommandSender(this::onFeedback);
        String cmd = "spark profiler start --only-ticks-over " + s.onlyTicksOverMs
                + " --timeout " + s.profileDurationSeconds + " --save-to-file";
        plugin.getServer().dispatchCommand(feedback, cmd);

        // spark 收到指令後若立刻回報忙碌，onFeedback 已把 running 設回 false
        if (!running) return false;

        String key = auto ? "spike.profile-started" : "spike.profile-manual-started";
        plugin.notifyAdmins(key, "seconds", s.profileDurationSeconds, "over", s.onlyTicksOverMs);
        notifyRequester(key, "seconds", s.profileDurationSeconds, "over", s.onlyTicksOverMs);
        plugin.getLogger().info(plugin.messages().console("console.profile-started", "seconds", s.profileDurationSeconds));

        // spark 只用弱參照保存指令的發送者，錄完後的「已存檔」訊息不一定收得到，
        // 所以在預計結束後到 spark 資料夾找新的報告檔，最多再等 2 分鐘
        scheduleLookup(s.profileDurationSeconds * 20L + 100L, 12);
        return true;
    }

    /** spark 的訊息；可能在主執行緒或 spark 的背景執行緒 */
    private void onFeedback(Component component) {
        String text = PlainTextComponentSerializer.plainText().serialize(component);
        if (text.contains("Profiler is already running")) {
            if (running && System.currentTimeMillis() - startedAt < 5000) {
                running = false;
                plugin.getLogger().info(plugin.messages().console("spike.profile-busy"));
                if (auto) runSync(() -> plugin.notifyAdmins("spike.profile-busy"));
            }
            return;
        }
        if (text.contains("Profiler operation failed") || text.contains("error occurred whilst saving")
                || text.contains("Tick counting is not supported")) {
            String reason = text.trim();
            runSync(() -> fail(reason));
            return;
        }
    }

    private void scheduleLookup(long delayTicks, int triesLeft) {
        watchdog = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!running) return;
            long since = startedAt;
            plugin.io().execute(() -> {
                Path found = findNewReport(since);
                runSync(() -> {
                    if (!running) return;
                    if (found != null) plugin.io().execute(() -> moveReport(found.toString()));
                    else if (triesLeft > 0) scheduleLookup(200L, triesLeft - 1);
                    else fail(plugin.messages().raw("spike.reason-timeout"));
                });
            });
        }, delayTicks);
    }

    /** spark 資料夾中，錄製開始後才建立的最新 profile-*.sparkprofile */
    private Path findNewReport(long since) {
        Path dir = sparkDir();
        Path best = null;
        long bestTime = since - 1000;
        try {
            for (Path p : listReports(dir)) {
                if (!p.getFileName().toString().startsWith("profile-")) continue;
                long t = modified(p);
                if (t > bestTime) {
                    best = p;
                    bestTime = t;
                }
            }
        } catch (IOException ignored) {
        }
        return best;
    }

    private Path sparkDir() {
        return plugin.getDataFolder().toPath().toAbsolutePath().getParent().resolve("spark");
    }

    /** 背景執行緒：驗證路徑並搬到 reports/ */
    private void moveReport(String raw) {
        Path src;
        try {
            src = Paths.get(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            runSync(() -> fail(plugin.messages().raw("spike.reason-move")));
            return;
        }
        Path sparkDir = sparkDir().normalize();
        String name = src.getFileName() == null ? "" : src.getFileName().toString();
        // 只搬 spark 資料夾內、副檔名為 .sparkprofile 的一般檔案
        if (!src.startsWith(sparkDir) || !name.endsWith(".sparkprofile") || !Files.isRegularFile(src)) {
            runSync(() -> fail(plugin.messages().raw("spike.reason-move")));
            return;
        }
        Path dir = plugin.reportsDir();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(startedAt));
        String base = (auto ? "spike-" : "manual-") + stamp;
        try {
            Files.createDirectories(dir);
            Path dst = dir.resolve(base + ".sparkprofile");
            for (int i = 2; Files.exists(dst); i++) dst = dir.resolve(base + "_" + i + ".sparkprofile");
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            long size = Files.size(dst);
            enforceRetention(dir);
            String file = dst.getFileName().toString();
            runSync(() -> {
                finish();
                plugin.notifyAdmins("spike.profile-saved", "file", file, "size", formatSize(size));
                notifyRequester("spike.profile-saved", "file", file, "size", formatSize(size));
                plugin.getLogger().info(plugin.messages().console("console.profile-saved", "file", file));
            });
        } catch (IOException e) {
            runSync(() -> fail(plugin.messages().raw("spike.reason-move") + ": " + e.getMessage()));
        }
    }

    private void fail(String reason) {
        if (!running) return;
        finish();
        plugin.notifyAdmins("spike.profile-failed", "reason", reason);
        notifyRequester("spike.profile-failed", "reason", reason);
        plugin.getLogger().warning(plugin.messages().console("console.profile-failed", "reason", reason));
    }

    private void finish() {
        running = false;
        requester = null;
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
    }

    private void notifyRequester(String key, Object... vars) {
        UUID id = requester;
        if (id == null) return;
        var p = plugin.getServer().getPlayer(id);
        // 已經透過 notifyAdmins 收到的就不重複
        if (p != null && !p.hasPermission(TickBlade.PERM_NOTIFY)) plugin.messages().send(p, key, vars);
    }

    private void runSync(Runnable r) {
        if (!plugin.isEnabled()) return;
        if (plugin.getServer().isPrimaryThread()) r.run();
        else plugin.getServer().getScheduler().runTask(plugin, r);
    }

    /** 超過檔案數或總容量時刪除最舊的報告（只處理 .sparkprofile） */
    private void enforceRetention(Path dir) throws IOException {
        Settings s = plugin.settings();
        List<Path> files = listReports(dir);
        files.sort(Comparator.comparingLong(SparkCapture::modified));
        long total = 0;
        for (Path p : files) total += size(p);
        int count = files.size();
        int i = 0;
        while (i < files.size() - 1 && (count > s.reportsMaxFiles || total > s.reportsMaxBytes)) {
            Path p = files.get(i++);
            long sz = size(p);
            Files.deleteIfExists(p);
            count--;
            total -= sz;
        }
    }

    public static List<Path> listReports(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> st = Files.list(dir)) {
            st.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".sparkprofile")).forEach(out::add);
        }
        return out;
    }

    public static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.0f KB", Math.max(1, bytes / 1024.0));
    }

    public void shutdown() {
        if (watchdog != null) watchdog.cancel();
        running = false;
    }
}
