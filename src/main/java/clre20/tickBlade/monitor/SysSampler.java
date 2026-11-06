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
