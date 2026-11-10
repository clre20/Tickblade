package clre20.tickBlade.monitor;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Messages;
import clre20.tickBlade.config.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 判斷「整個程序被凍住」的原因。
 * StackCapture 的背景執行緒發現自己晚醒很久時呼叫 onFreeze；10 秒後（等 JFR 事件送達）
 * 對照同一段時間的 JVM 安全點、容器 CPU 限流、PSI 壓力與 steal，給出判斷並寫入 reports/freezes.log。
 * 除了 stats 與 flush，其他方法只在 StackCapture 的背景執行緒呼叫。
 */
public final class FreezeDiagnosis {

    public enum Verdict { SAFEPOINT, THROTTLE, MEMORY, IO, CPU, STEAL, HOST, UNKNOWN, STALL_USER, STALL_KERNEL }

    /** 凍結那一段時間內，主執行緒與背景執行緒各自被記了多少 CPU 時間（毫秒；-1 = 無法取得） */
    record ThreadTimes(long mainCpuMs, long mainUserMs, long selfCpuMs, long selfUserMs) {
    }

    private record Pending(long startMs, long endMs, long ms, boolean inTick, String usage, ThreadTimes times,
                           SysSampler.Extra xBefore, SysSampler.Extra xAfter,
                           SysSampler.Sample before, SysSampler.Sample after) {
    }

    private static final long RESOLVE_DELAY_MS = 10_000;
    private static final long SAMPLE_INTERVAL_NS = 1_000_000_000L;
    private static final int MAX_PENDING = 100;
    private static final int MAX_LINES = 500;

    private final TickBlade plugin;
    private SysSampler sampler;
    private volatile JfrWatch jfr;
    private volatile String jfrError;
    private SysSampler.Sample last;
    private SysSampler.Extra lastExtra;
    private volatile String cpuMax;
    private long lastSampleNs;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private volatile boolean hasThrottle, hasPsi, hasSteal;

    /** 重載時換新的 FreezeDiagnosis，統計與待寫入的紀錄沿用 */
    static final class Shared {
        final List<String> lines = new ArrayList<>();
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalMs = new AtomicLong();
        final AtomicLongArray byVerdict = new AtomicLongArray(Verdict.values().length);
    }

    private final List<String> lines;
    private final AtomicLong count;
    private final AtomicLong totalMs;
    private final AtomicLongArray byVerdict;

    FreezeDiagnosis(TickBlade plugin, Shared shared) {
        this.plugin = plugin;
        this.lines = shared.lines;
        this.count = shared.count;
        this.totalMs = shared.totalMs;
        this.byVerdict = shared.byVerdict;
    }

    /** 背景執行緒：建立取樣器、啟動 JFR */
    void start(Settings s) {
        sampler = new SysSampler();
        hasThrottle = sampler.hasThrottle();
        hasPsi = sampler.hasPsi();
        hasSteal = sampler.hasSteal();
        sampler.locateMainThread();
        last = sampler.sample();
        lastExtra = sampler.extra();
        lastSampleNs = System.nanoTime();
        cpuMax = sampler.cpuMax();
        if (cpuMax != null) plugin.getLogger().info(plugin.messages().console("console.cpu-max", "value", cpuMax));
        jfrError = null;
        if (!s.freezeJfr) {
            jfrError = "config";
            return;
        }
        try {
            JfrWatch w = new JfrWatch(s.freezeSafepointMs);
            w.start();
            jfr = w;
            plugin.getLogger().info(plugin.messages().console("console.jfr-started", "ms", s.freezeSafepointMs));
        } catch (Throwable t) { // 沒有 jdk.jfr 模組、被停用或沒有權限
            jfrError = t.getClass().getSimpleName();
            plugin.getLogger().warning(plugin.messages().console("console.jfr-failed", "reason", String.valueOf(t)));
        }
    }

    /** 背景執行緒：關閉 JFR */
    void stop() {
        JfrWatch w = jfr;
        jfr = null;
        if (w != null) {
            try {
                w.close();
            } catch (Throwable ignored) {
            }
        }
    }

    void sampleIfDue(long nowNs) {
        if (sampler == null || nowNs - lastSampleNs < SAMPLE_INTERVAL_NS) return;
        last = sampler.sample();
        lastExtra = sampler.extra();
        lastSampleNs = nowNs;
    }

    void onFreeze(long startMs, long endMs, long ms, boolean inTick, String usage, ThreadTimes times) {
        if (sampler == null) return;
        SysSampler.Sample after = sampler.sample();
        SysSampler.Extra xAfter = sampler.extra();
        if (pending.size() < MAX_PENDING) {
            pending.addLast(new Pending(startMs, endMs, ms, inTick, usage, times, lastExtra, xAfter, last, after));
        }
        last = after;
        lastExtra = xAfter;
        lastSampleNs = System.nanoTime();
    }

    void resolveDue() {
        long now = System.currentTimeMillis();
        while (!pending.isEmpty() && now - pending.peekFirst().endMs() >= RESOLVE_DELAY_MS) {
            resolve(pending.pollFirst());
        }
    }

    private static long delta(long a, long b) {
        return a < 0 || b < 0 || b < a ? -1 : b - a;
    }

    private void resolve(Pending p) {
        Messages m = plugin.messages();
        String na = m.raw("freeze.na");

        // 1. JVM 安全點
        long spMs = 0;
        String op = "?";
        long syncMs = -1;
        JfrWatch w = jfr;
        if (w != null) {
            JfrWatch.Info best = w.longestOverlapping(p.startMs() - 500, p.endMs() + 500);
            if (best != null) {
                spMs = best.totalMs();
                op = best.operation();
                syncMs = best.syncMs();
            }
        }
        // 2. 系統統計
        SysSampler.Sample a = p.before(), b = p.after();
        long thrUs = delta(a.throttledUs(), b.throttledUs());
        long cpuUs = delta(a.cpuSomeUs(), b.cpuSomeUs());
        long ioUs = delta(a.ioFullUs(), b.ioFullUs());
        long memUs = delta(a.memFullUs(), b.memFullUs());
        long dSteal = delta(a.stealJiffies(), b.stealJiffies());
        long dTotal = delta(a.totalJiffies(), b.totalJiffies());
        double stealPct = dSteal < 0 || dTotal <= 0 ? -1 : dSteal * 100.0 / dTotal;

        // 3. 執行緒被記的 CPU 時間：凍結期間「佔著 CPU 卻沒有前進」不可能是被限流（被限流時不會記 CPU 時間）
        ThreadTimes t = p.times();
        long stallCpu = -1, stallUser = -1;
        if (t != null) {
            if (p.inTick() && t.mainCpuMs() >= t.selfCpuMs()) {
                stallCpu = t.mainCpuMs();
                stallUser = t.mainUserMs();
            } else {
                stallCpu = t.selfCpuMs();
                stallUser = t.selfUserMs();
            }
        }

        double ms = p.ms();
        Verdict v;
        if (spMs >= ms * 0.5) v = Verdict.SAFEPOINT;
        else if (stallCpu >= ms * 0.5) v = stallUser >= stallCpu * 0.5 ? Verdict.STALL_USER : Verdict.STALL_KERNEL;
        else if (thrUs >= 0 && thrUs / 1000.0 >= ms * 0.3) v = Verdict.THROTTLE;
        else if (memUs >= 0 && memUs / 1000.0 >= ms * 0.3) v = Verdict.MEMORY;
        else if (ioUs >= 0 && ioUs / 1000.0 >= ms * 0.3) v = Verdict.IO;
        else if (cpuUs >= 0 && cpuUs / 1000.0 >= ms * 0.5) v = Verdict.CPU;
        else if (stealPct >= 20) v = Verdict.STEAL;
        else if (w != null) v = Verdict.HOST;
        else v = Verdict.UNKNOWN;

        count.incrementAndGet();
        totalMs.addAndGet(p.ms());
        byVerdict.incrementAndGet(v.ordinal());

        String verdict = v == Verdict.SAFEPOINT
                ? m.raw("freeze.verdict.safepoint", "op", op)
                : m.raw("freeze.verdict." + v.name().toLowerCase().replace('_', '-'));
        String safepoint = w == null ? na : spMs == 0 ? m.raw("freeze.none")
                : m.raw("freeze.safepoint-detail", "ms", spMs, "op", op, "sync", syncMs < 0 ? "?" : syncMs);
        String details = m.raw("freeze.details",
                "safepoint", safepoint,
                "throttle", thrUs < 0 ? na : thrUs / 1000,
                "cpu", cpuUs < 0 ? na : cpuUs / 1000,
                "io", ioUs < 0 ? na : ioUs / 1000,
                "memory", memUs < 0 ? na : memUs / 1000,
                "steal", stealPct < 0 ? na : String.format("%.1f", stealPct),
                "psi", sampler == null ? "-" : sampler.psiSource());

        SysSampler.Extra xa = p.xBefore(), xb = p.xAfter();
        String sched = null;
        if (t != null && xa != null && xb != null) {
            sched = m.raw("freeze.sched",
                    "maincpu", num(t.mainCpuMs(), na), "mainsys", sys(t.mainCpuMs(), t.mainUserMs(), na),
                    "mainwait", nsToMs(delta(xa.mainWaitNs(), xb.mainWaitNs()), na),
                    "selfcpu", num(t.selfCpuMs(), na), "selfsys", sys(t.selfCpuMs(), t.selfUserMs(), na),
                    "selfwait", nsToMs(delta(xa.selfWaitNs(), xb.selfWaitNs()), na),
                    "procuser", num(delta(xa.procUserMs(), xb.procUserMs()), na),
                    "procsys", num(delta(xa.procSysMs(), xb.procSysMs()), na),
                    "minflt", num(delta(xa.minFlt(), xb.minFlt()), na), "majflt", num(delta(xa.majFlt(), xb.majFlt()), na),
                    "thrperiods", num(delta(xa.throttledPeriods(), xb.throttledPeriods()), na),
                    "periods", num(delta(xa.periods(), xb.periods()), na),
                    "cpumax", cpuMax == null ? na : cpuMax,
                    "numa", num(delta(xa.numaFaults(), xb.numaFaults()), na),
                    "compact", num(delta(xa.compactStall(), xb.compactStall()), na),
                    "reclaim", num(delta(xa.directReclaim(), xb.directReclaim()), na));
        }
        final String schedLine = sched;

        plugin.getLogger().warning(m.console("console.freeze", "ms", p.ms(), "verdict", verdict));
        String line = m.raw("freeze.log-line",
                "time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(p.startMs())),
                "ms", p.ms(),
                "intick", m.raw(p.inTick() ? "freeze.yes" : "freeze.no"),
                "verdict", verdict);
        synchronized (lines) {
            if (lines.size() < MAX_LINES) lines.add(Messages.stripColor(line) + "\n  " + Messages.stripColor(details) + "\n"
                    + (schedLine == null ? "" : "  " + Messages.stripColor(schedLine) + "\n")
                    + (p.usage() == null ? "" : "  " + Messages.stripColor(p.usage()) + "\n"));
        }
    }

    private static Object num(long v, String na) {
        return v < 0 ? na : v;
    }

    private static Object nsToMs(long ns, String na) {
        return ns < 0 ? na : ns / 1_000_000L;
    }

    /** 核心態 CPU = 總 CPU − 使用者態 */
    private static Object sys(long cpuMs, long userMs, String na) {
        return cpuMs < 0 || userMs < 0 ? na : Math.max(0, cpuMs - userMs);
    }

    public String cpuMax() {
        return cpuMax;
    }

    /** I/O 執行緒：批次寫入 reports/freezes.log，超過大小改名為 freezes.log.1 */
    public void flush() {
        List<String> batch;
        synchronized (lines) {
            if (lines.isEmpty()) return;
            batch = new ArrayList<>(lines);
            lines.clear();
        }
        Path dir = plugin.reportsDir();
        Path file = dir.resolve("freezes.log");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file) && Files.size(file) > plugin.settings().freezeLogMaxBytes) {
                Files.move(file, dir.resolve("freezes.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            StringBuilder sb = new StringBuilder();
            for (String s : batch) sb.append(s);
            Files.writeString(file, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot write freezes.log: " + e.getMessage());
        }
    }

    public long count() {
        return count.get();
    }

    public long totalMs() {
        return totalMs.get();
    }

    public long count(Verdict v) {
        return byVerdict.get(v.ordinal());
    }

    public boolean jfrRunning() {
        return jfr != null;
    }

    public String jfrError() {
        return jfrError;
    }

    public boolean hasThrottle() {
        return hasThrottle;
    }

    public boolean hasPsi() {
        return hasPsi;
    }

    public boolean hasSteal() {
        return hasSteal;
    }
}
