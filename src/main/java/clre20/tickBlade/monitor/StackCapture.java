package clre20.tickBlade.monitor;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Settings;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 卡頓當下擷取主執行緒堆疊。
 * spark 的取樣抓不到卡在系統呼叫（例如寫硬碟）的主執行緒；這裡由一條背景執行緒
 * 定時檢查目前的 tick 跑了多久，超過門檻就直接讀主執行緒的堆疊，寫進 reports/stacks.log。
 * 主執行緒上只做兩次 System.nanoTime() 記錄 tick 開始／結束。
 */
public final class StackCapture implements Listener {

    private static final int MAX_PENDING = 200;
    /** 這些套件開頭的是伺服器或 Java 本身，其他的視為插件 */
    private static final String[] CORE_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.", "com.mojang.", "ca.spottedleaf.",
            "io.papermc.", "org.bukkit.", "com.destroystokyo.", "io.netty.", "it.unimi.", "org.spigotmc.",
            "co.aikar.", "net.kyori.", "com.google.", "org.apache.", "org.slf4j.", "org.jline."};

    private final TickBlade plugin;
    private final Thread mainThread;
    private ScheduledExecutorService watcher;
    private final FreezeDiagnosis.Shared shared = new FreezeDiagnosis.Shared();
    private volatile FreezeDiagnosis diagnosis;
    private long lastCheckEnd;             // 只在 watcher 執行緒存取

    private volatile long tickStart;      // 0 = 目前不在 tick 中
    private volatile long tickNumber;
    private final ThreadCpuTracker cpu = new ThreadCpuTracker();
    private long lastCpuSampleNs;          // 只在 watcher 執行緒存取
    private long cpuTick = -1;             // 已擷取、等結束後要統計 CPU 的 tick
    private long cpuTickStartWall;
    private long capturedTick = -1;        // 只在 watcher 執行緒存取
    private int capturedInTick;
    private long lastCaptureAt;

    private final List<String> pending = new ArrayList<>();
    private volatile long totalCaptures;
    private volatile String lastWhere;
    private volatile long lastWhereAt;

    public StackCapture(TickBlade plugin) {
        this.plugin = plugin;
        this.mainThread = Thread.currentThread(); // 在 onEnable（主執行緒）建立
        this.diagnosis = new FreezeDiagnosis(plugin, shared);
    }

    // ---------- 主執行緒：只記錄時間 ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTickStart(ServerTickStartEvent e) {
        tickNumber = e.getTickNumber();
        tickStart = System.nanoTime();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent e) {
        tickStart = 0;
    }

    // ---------- 背景執行緒 ----------

    public void start() {
        stop(false);
        Settings s = plugin.settings();
        if (!s.stackEnabled && !s.freezeEnabled) return;
        ScheduledExecutorService w = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TickBlade-StackCapture");
            t.setDaemon(true);
            return t;
        });
        watcher = w;
        lastCheckEnd = 0;
        FreezeDiagnosis d = new FreezeDiagnosis(plugin, shared);
        diagnosis = d;
        if (s.freezeEnabled) w.execute(() -> d.start(s));
        w.scheduleWithFixedDelay(() -> check(d), 1000, s.stackCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** 停止背景執行緒；JFR 在背景執行緒上關閉。wait = 等它結束（關服時） */
    public void stop(boolean wait) {
        ScheduledExecutorService w = watcher;
        watcher = null;
        if (w == null) return;
        FreezeDiagnosis d = diagnosis;
        try {
            w.execute(d::stop);
        } catch (RuntimeException ignored) {
        }
        w.shutdown(); // 週期任務取消，已排入的 stop 會執行
        if (wait) {
            try {
                if (!w.awaitTermination(3, TimeUnit.SECONDS)) w.shutdownNow();
            } catch (InterruptedException e) {
                w.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void check(FreezeDiagnosis diagnosis) {
        try {
            Settings s = plugin.settings();
            long now = System.nanoTime();
            if (s.freezeEnabled) {
                // 背景執行緒自己晚醒很久 = 整個程序被凍住（不是只有主執行緒慢）
                if (lastCheckEnd != 0) {
                    long late = (now - lastCheckEnd) / 1_000_000L - s.stackCheckIntervalMs;
                    if (late >= s.freezeThresholdMs && plugin.uptimeSeconds() >= s.startupGraceSeconds) {
                        long end = System.currentTimeMillis();
                        boolean inTick = tickStart != 0;
                        // 在 tick 中的凍結會在 tick 結束時另外統計，這裡不重複累計
                        String usage = s.threadCpuEnabled
                                ? formatUsage(cpu.report(end - late, s.threadCpuTop, !inTick)) : null;
                        diagnosis.onFreeze(end - late, end, late, inTick, usage);
                    }
                }
                diagnosis.sampleIfDue(now);
                diagnosis.resolveDue();
            }
            if (s.threadCpuEnabled) {
                if (now - lastCpuSampleNs >= 1_000_000_000L) {
                    cpu.sample();
                    lastCpuSampleNs = now;
                }
                reportTickCpuIfEnded(s);
            }
            if (s.stackEnabled) captureIfSlow(s, now);
        } catch (Throwable t) {
            plugin.getLogger().warning("Stack capture failed: " + t);
        } finally {
            lastCheckEnd = System.nanoTime();
        }
    }

    private void captureIfSlow(Settings s, long now) {
        long start = tickStart;
        if (start == 0) return;
        long ms = (now - start) / 1_000_000L;
        if (ms < s.stackThresholdMs) return;
        if (plugin.uptimeSeconds() < s.startupGraceSeconds) return;
        long tick = tickNumber;
        if (tick != capturedTick) {
            capturedTick = tick;
            capturedInTick = 0;
        } else {
            if (capturedInTick >= s.stackMaxPerTick) return;
            if ((now - lastCaptureAt) / 1_000_000L < s.stackIntervalMs) return;
        }
        StackTraceElement[] stack = mainThread.getStackTrace();
        // 擷取當下 tick 已經結束就不算（避免抓到下一個 tick 的正常畫面）
        if (tickStart != start) return;
        capturedInTick++;
        lastCaptureAt = now;
        if (capturedInTick == 1 && s.threadCpuEnabled) {
            cpuTick = tick;
            cpuTickStartWall = System.currentTimeMillis() - ms;
        }
        record(ms, capturedInTick, stack, s);
    }

    /** 被擷取過的 tick 結束後，記錄這次卡頓期間各執行緒用掉的 CPU */
    private void reportTickCpuIfEnded(Settings s) {
        if (cpuTick < 0) return;
        if (tickStart != 0 && tickNumber == cpuTick) return; // 還沒結束
        long startWall = cpuTickStartWall;
        cpuTick = -1;
        ThreadCpuTracker.Report r = cpu.report(startWall, s.threadCpuTop, true);
        String usage = formatUsage(r);
        if (usage == null) return;
        var m = plugin.messages();
        String entry = m.raw("cpu.tick-entry",
                "time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()),
                "ms", System.currentTimeMillis() - startWall, "usage", usage);
        synchronized (pending) {
            if (pending.size() < MAX_PENDING) pending.add(clre20.tickBlade.config.Messages.stripColor(entry) + "\n\n");
        }
    }

    /** 一行文字：期間、CPU 總量與最耗 CPU 的執行緒；資料不足回傳 null */
    private String formatUsage(ThreadCpuTracker.Report r) {
        if (r == null || r.top().isEmpty()) return null;
        var m = plugin.messages();
        StringBuilder list = new StringBuilder();
        for (ThreadCpuTracker.Usage u : r.top()) {
            if (list.length() > 0) list.append(m.raw("cpu.separator"));
            String name = u.name().equals(ThreadCpuTracker.JVM_INTERNAL) ? m.raw("cpu.jvm-internal") : u.name();
            String where = u.where() == null ? "" : m.raw("cpu.where", "where", u.where());
            list.append(m.raw("cpu.item", "name", name, "where", where, "ms", u.ms()));
        }
        String cores = r.windowMs() <= 0 ? "?" : String.format("%.1f", r.totalMs() / (double) r.windowMs());
        return m.raw("cpu.line", "window", r.windowMs(), "total", r.totalMs(), "cores", cores, "list", list);
    }

    static String[] corePrefixes() {
        return CORE_PREFIXES.clone();
    }

    /** 累計最耗 CPU 的前 n 項（名稱已合併數字），給 /tickb status */
    public java.util.List<java.util.Map.Entry<String, Long>> cpuTotals(int n) {
        return cpu.totals(n);
    }

    public boolean cpuSupported() {
        return cpu.supported();
    }

    public static boolean isJvmInternal(String key) {
        return ThreadCpuTracker.JVM_INTERNAL.equals(key);
    }

    private void record(long ms, int nth, StackTraceElement[] stack, Settings s) {
        String where = summarize(stack);
        totalCaptures++;
        lastWhere = where;
        lastWhereAt = System.currentTimeMillis();
        plugin.getLogger().warning(plugin.messages().console("console.stack-captured", "ms", ms, "where", where));

        StringBuilder sb = new StringBuilder(2048);
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
                .append("  tick 已卡 ").append(ms).append(" ms（這次卡頓第 ").append(nth).append(" 次擷取）")
                .append("  players=").append(plugin.getServer().getOnlinePlayers().size()).append('\n')
                .append("  可能來源：").append(where).append('\n');
        int n = Math.min(stack.length, s.stackMaxFrames);
        for (int i = 0; i < n; i++) sb.append("    at ").append(stack[i]).append('\n');
        if (stack.length > n) sb.append("    ...（還有 ").append(stack.length - n).append(" 層）\n");
        sb.append('\n');
        synchronized (pending) {
            if (pending.size() < MAX_PENDING) pending.add(sb.toString());
        }
    }

    /** 第一個不是伺服器／Java 本身的呼叫者；全是伺服器程式時取最上面幾個有意義的方法 */
    static String summarize(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            if (!isCore(e.getClassName())) {
                // 插件的類別載入器名稱是 jar 檔名（例如 Cback.jar），比套件名稱好認
                String jar = e.getClassLoaderName();
                return (jar != null && jar.endsWith(".jar") ? "[" + jar + "] " : "") + shortName(e);
            }
        }
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")) continue;
            if (sb.length() > 0) sb.append(" ← ");
            sb.append(shortName(e));
            if (++added >= 3) break;
        }
        if (sb.length() == 0 && stack.length > 0) sb.append(shortName(stack[0]));
        return sb.toString();
    }

    private static boolean isCore(String className) {
        for (String p : CORE_PREFIXES) if (className.startsWith(p)) return true;
        return false;
    }

    private static String shortName(StackTraceElement e) {
        String c = e.getClassName();
        int dot = c.lastIndexOf('.');
        String pkg = dot > 0 ? c.substring(0, dot) : "";
        String simple = dot > 0 ? c.substring(dot + 1) : c;
        // 保留前兩段套件名稱，方便認出是哪個插件
        String[] parts = pkg.split("\\.");
        String head = parts.length >= 2 ? parts[0] + "." + parts[1] + "…" : pkg;
        return (head.isEmpty() ? "" : head + ".") + simple + "." + e.getMethodName()
                + (e.getLineNumber() > 0 ? ":" + e.getLineNumber() : "");
    }

    /** 背景 I/O 執行緒呼叫：批次寫入 reports/stacks.log，超過大小改名為 stacks.log.1 */
    public void flush() {
        List<String> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        Path dir = plugin.reportsDir();
        Path file = dir.resolve("stacks.log");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file) && Files.size(file) > plugin.settings().stackLogMaxBytes) {
                Files.move(file, dir.resolve("stacks.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            StringBuilder sb = new StringBuilder();
            for (String s : batch) sb.append(s);
            Files.writeString(file, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot write stacks.log: " + e.getMessage());
        }
    }

    public FreezeDiagnosis diagnosis() {
        return diagnosis;
    }

    public long totalCaptures() {
        return totalCaptures;
    }

    public String lastWhere() {
        return lastWhere;
    }

    public long lastWhereAt() {
        return lastWhereAt;
    }
}
