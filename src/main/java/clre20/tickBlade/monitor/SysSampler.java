package clre20.tickBlade.monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 讀取 Linux 核心提供的系統統計（/proc、/sys 是記憶體中的虛擬檔案，不會讀寫硬碟）。
 * 路徑全部是固定常數；檔案不存在（Windows、舊核心、沒有權限）時該項目為 -1。
 * 只在 StackCapture 的背景執行緒使用。
 */
final class SysSampler {

    /** 一次取樣；累計值，單位見欄位名稱，-1 = 無法取得 */
    record Sample(long wallMs, long throttledUs, long cpuSomeUs, long ioFullUs, long memFullUs,
                  long stealJiffies, long totalJiffies) {
    }

    /** 第二組統計（1.3.2）：全部是累計值，-1 = 無法取得 */
    record Extra(long minFlt, long majFlt, long procUserMs, long procSysMs, long periods, long throttledPeriods,
                 long mainRunNs, long mainWaitNs, long selfRunNs, long selfWaitNs,
                 long numaFaults, long compactStall, long directReclaim) {
    }

    private static final Path SELF_STAT = Path.of("/proc/self/stat");
    private static final Path SELF_SCHED = Path.of("/proc/thread-self/schedstat");
    private static final Path VMSTAT = Path.of("/proc/vmstat");
    private static final Path CG2_CPU_MAX = Path.of("/sys/fs/cgroup/cpu.max");
    private static final Path[] CG1_QUOTA = {
            Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"), Path.of("/sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us")};
    private Path mainSched;   // /proc/self/task/<tid>/schedstat（主執行緒）
    private long clockTick = 100;

    private static final Path CG2_CPU_STAT = Path.of("/sys/fs/cgroup/cpu.stat");
    private static final Path[] CG1_CPU_STAT = {
            Path.of("/sys/fs/cgroup/cpu/cpu.stat"), Path.of("/sys/fs/cgroup/cpu,cpuacct/cpu.stat")};
    private static final String[] PSI_DIRS = {"/sys/fs/cgroup/", "/proc/pressure/"};
    private static final Path PROC_STAT = Path.of("/proc/stat");

    private Path cpuStat;
    private boolean cpuStatV2;
    private Path cpuPsi, ioPsi, memPsi;
    private String psiSource = "-";
    private boolean procStat;

    SysSampler() {
        if (Files.isReadable(CG2_CPU_STAT)) {
            cpuStat = CG2_CPU_STAT;
            cpuStatV2 = true;
        } else {
            for (Path p : CG1_CPU_STAT) {
                if (Files.isReadable(p)) {
                    cpuStat = p;
                    break;
                }
            }
        }
        for (String dir : PSI_DIRS) {
            Path c = Path.of(dir + (dir.startsWith("/sys") ? "cpu.pressure" : "cpu"));
            Path i = Path.of(dir + (dir.startsWith("/sys") ? "io.pressure" : "io"));
            Path m = Path.of(dir + (dir.startsWith("/sys") ? "memory.pressure" : "memory"));
            if (Files.isReadable(c) && Files.isReadable(i) && Files.isReadable(m) && readable(c)) {
                cpuPsi = c;
                ioPsi = i;
                memPsi = m;
                psiSource = dir.startsWith("/sys") ? "cgroup" : "system";
                break;
            }
        }
        procStat = Files.isReadable(PROC_STAT);
    }

    /** 有些環境檔案存在但讀取會失敗（例如 PSI 被核心停用） */
    private static boolean readable(Path p) {
        try {
            Files.readAllLines(p, StandardCharsets.US_ASCII);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** 找出主執行緒（核心裡的名稱是 "Server thread"）的 schedstat 檔；只在啟動時掃一次 /proc/self/task */
    void locateMainThread() {
        mainSched = null;
        try (var dirs = Files.newDirectoryStream(Path.of("/proc/self/task"))) {
            for (Path d : dirs) {
                Path comm = d.resolve("comm");
                try {
                    if (Files.readString(comm, StandardCharsets.US_ASCII).trim().equals("Server thread")) {
                        Path f = d.resolve("schedstat");
                        if (Files.isReadable(f)) mainSched = f;
                        break;
                    }
                } catch (IOException | RuntimeException ignored) {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** 容器的 CPU 額度設定，例如 "400000 100000"（額度 週期，微秒）；無法取得回傳 null */
    String cpuMax() {
        try {
            if (Files.isReadable(CG2_CPU_MAX)) return Files.readString(CG2_CPU_MAX, StandardCharsets.US_ASCII).trim();
            for (Path q : CG1_QUOTA) {
                if (!Files.isReadable(q)) continue;
                Path per = q.resolveSibling("cpu.cfs_period_us");
                return Files.readString(q, StandardCharsets.US_ASCII).trim() + " "
                        + Files.readString(per, StandardCharsets.US_ASCII).trim();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    private static long[] sched(Path p) {
        if (p == null) return new long[]{-1, -1};
        try {
            String[] f = Files.readString(p, StandardCharsets.US_ASCII).trim().split("\\s+");
            return new long[]{Long.parseLong(f[0]), Long.parseLong(f[1])};
        } catch (IOException | RuntimeException e) {
            return new long[]{-1, -1};
        }
    }

    Extra extra() {
        long minFlt = -1, majFlt = -1, user = -1, sys = -1;
        try {
            String st = Files.readString(SELF_STAT, StandardCharsets.US_ASCII);
            // 第 2 欄（程式名稱）可能含空白，從最後一個 ')' 之後開始切
            String[] f = st.substring(st.lastIndexOf(')') + 2).trim().split("\\s+");
            minFlt = Long.parseLong(f[7]);   // 欄位 10
            majFlt = Long.parseLong(f[9]);   // 欄位 12
            user = Long.parseLong(f[11]) * 1000 / clockTick; // 欄位 14 utime
            sys = Long.parseLong(f[12]) * 1000 / clockTick;  // 欄位 15 stime
        } catch (IOException | RuntimeException ignored) {
        }
        long periods = -1, thrPeriods = -1;
        if (cpuStat != null) {
            periods = statValue(cpuStat, "nr_periods");
            thrPeriods = statValue(cpuStat, "nr_throttled");
        }
        long[] main = sched(mainSched), self = sched(SELF_SCHED);
        long numa = -1, compact = -1, reclaim = -1;
        try {
            long r = 0;
            boolean anyReclaim = false;
            for (String line : Files.readAllLines(VMSTAT, StandardCharsets.US_ASCII)) {
                if (line.startsWith("numa_hint_faults ")) numa = Long.parseLong(line.substring(17).trim());
                else if (line.startsWith("compact_stall ")) compact = Long.parseLong(line.substring(14).trim());
                else if (line.startsWith("allocstall")) {
                    r += Long.parseLong(line.substring(line.indexOf(' ') + 1).trim());
                    anyReclaim = true;
                }
            }
            if (anyReclaim) reclaim = r;
        } catch (IOException | RuntimeException ignored) {
        }
        return new Extra(minFlt, majFlt, user, sys, periods, thrPeriods, main[0], main[1], self[0], self[1],
                numa, compact, reclaim);
    }

    boolean hasThrottle() {
        return cpuStat != null;
    }

    boolean hasPsi() {
        return cpuPsi != null;
    }

    boolean hasSteal() {
        return procStat;
    }

    String psiSource() {
        return psiSource;
    }

    Sample sample() {
        long throttled = -1;
        if (cpuStat != null) {
            // v2: throttled_usec（微秒）；v1: throttled_time（奈秒）
            long v = statValue(cpuStat, cpuStatV2 ? "throttled_usec" : "throttled_time");
            throttled = v < 0 ? -1 : (cpuStatV2 ? v : v / 1000);
        }
        long cpuSome = psiTotal(cpuPsi, "some");
        long ioFull = psiTotal(ioPsi, "full");
        long memFull = psiTotal(memPsi, "full");
        long steal = -1, total = -1;
        if (procStat) {
            try (var lines = Files.lines(PROC_STAT, StandardCharsets.US_ASCII)) {
                String first = lines.findFirst().orElse("");
                if (first.startsWith("cpu ")) {
                    String[] f = first.trim().split("\\s+");
                    long sum = 0;
                    for (int k = 1; k < f.length && k <= 8; k++) sum += Long.parseLong(f[k]);
                    total = sum;
                    steal = f.length > 8 ? Long.parseLong(f[8]) : 0;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return new Sample(System.currentTimeMillis(), throttled, cpuSome, ioFull, memFull, steal, total);
    }

    private static long statValue(Path p, String key) {
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.US_ASCII)) {
                if (line.startsWith(key + " ")) return Long.parseLong(line.substring(key.length() + 1).trim());
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return -1;
    }

    /** PSI 格式：some avg10=0.00 avg60=0.00 avg300=0.00 total=123（微秒） */
    private static long psiTotal(Path p, String kind) {
        if (p == null) return -1;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.US_ASCII)) {
                if (!line.startsWith(kind + " ")) continue;
                int i = line.indexOf("total=");
                if (i >= 0) return Long.parseLong(line.substring(i + 6).trim());
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return -1;
    }
}
