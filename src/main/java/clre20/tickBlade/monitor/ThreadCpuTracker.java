package clre20.tickBlade.monitor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每秒記錄每條 Java 執行緒用掉的 CPU 時間，卡頓或程序凍結時算出「這段期間誰最耗 CPU」。
 * 只保留最近 MAX_SNAPSHOTS 秒的快照（只存 thread id 與數字，不持有 Thread 物件）。
 * 除了 totals()，只在 StackCapture 的背景執行緒使用。
 */
final class ThreadCpuTracker {

    /** 一條執行緒（或 JVM 內部）在一段期間用掉的 CPU */
    record Usage(String name, String where, long ms) {
    }

    record Report(long windowMs, long totalMs, List<Usage> top) {
    }

    private record Snapshot(long wallMs, long[] ids, long[] cpuNs, long processNs) {
    }

    static final String JVM_INTERNAL = "\u0000jvm"; // 代表 GC、JIT 等不是 Java 執行緒的部分
    private static final int MAX_SNAPSHOTS = 30;
    private static final int MAX_TOTAL_KEYS = 200;
    private static final String[] CORE_PREFIXES = StackCapture.corePrefixes();

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean os;
    private final boolean supported;
    private final ArrayDeque<Snapshot> history = new ArrayDeque<>();
    /** thread id → 名稱；只在出現新的執行緒時查詢一次，結束的執行緒在快照都淘汰後移除 */
    private final HashMap<Long, String> names = new HashMap<>();
    /** 累計（名稱的數字換成 #）→ 毫秒，給 /tickb status */
    private final ConcurrentHashMap<String, Long> totals = new ConcurrentHashMap<>();

    ThreadCpuTracker() {
        boolean ok;
        try {
            ok = threads.isThreadCpuTimeSupported();
            if (ok && !threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
            ok = ok && threads.isThreadCpuTimeEnabled();
        } catch (RuntimeException e) {
            ok = false;
        }
        supported = ok;
        com.sun.management.OperatingSystemMXBean o = null;
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean x) o = x;
        } catch (RuntimeException ignored) {
        }
        os = o;
    }

    boolean supported() {
        return supported;
    }

    private Snapshot take() {
        long[] ids = threads.getAllThreadIds();
        long[] cpu;
        if (threads instanceof com.sun.management.ThreadMXBean sun) {
            cpu = sun.getThreadCpuTime(ids); // 一次取全部
        } else {
            cpu = new long[ids.length];
            for (int i = 0; i < ids.length; i++) cpu[i] = threads.getThreadCpuTime(ids[i]);
        }
        long proc = os == null ? -1 : os.getProcessCpuTime();
        return new Snapshot(System.currentTimeMillis(), ids, cpu, proc);
    }

    /** 背景執行緒每秒呼叫一次 */
    synchronized void sample() {
        if (!supported) return;
        try {
            Snapshot s = take();
            if (history.size() >= MAX_SNAPSHOTS) history.pollFirst();
            history.addLast(s);
            learnNames(s.ids());
        } catch (RuntimeException ignored) {
        }
    }

    /** 新出現的執行緒查一次名稱（一般執行緒都是長期存在的，很少需要查） */
    private void learnNames(long[] ids) {
        int unknown = 0;
        for (long id : ids) if (!names.containsKey(id)) unknown++;
        if (unknown > 0) {
            long[] q = new long[unknown];
            int k = 0;
            for (long id : ids) if (!names.containsKey(id)) q[k++] = id;
            ThreadInfo[] infos = threads.getThreadInfo(q, 0);
            for (int i = 0; i < q.length; i++) {
                names.put(q[i], infos[i] == null ? "#" + q[i] : infos[i].getThreadName());
            }
        }
        if (names.size() > ids.length * 2 + 64) {
            java.util.HashSet<Long> alive = new java.util.HashSet<>();
            for (Snapshot s : history) for (long id : s.ids()) alive.add(id);
            names.keySet().retainAll(alive);
        }
    }

    /** 從 startMs 之前最後一次快照到現在，各執行緒用掉的 CPU；資料不足時回傳 null */
    synchronized Report report(long startMs, int topN, boolean addToTotals) {
        if (!supported || history.isEmpty()) return null;
        Snapshot base = null;
        for (Snapshot s : history) {
            if (s.wallMs() <= startMs) base = s;
            else break;
        }
        if (base == null) base = history.peekFirst();
        Snapshot now;
        try {
            now = take();
        } catch (RuntimeException e) {
            return null;
        }
        Map<Long, Long> before = new HashMap<>(base.ids().length * 2);
        for (int i = 0; i < base.ids().length; i++) before.put(base.ids()[i], base.cpuNs()[i]);

        // 每條執行緒在期間內最後一次看到的值（期間內結束的執行緒用最後一次快照）
        Map<Long, Long> latest = new HashMap<>(now.ids().length * 2);
        boolean after = false;
        for (Snapshot s : history) {
            if (s == base) {
                after = true;
                continue;
            }
            if (!after) continue;
            for (int i = 0; i < s.ids().length; i++) if (s.cpuNs()[i] >= 0) latest.put(s.ids()[i], s.cpuNs()[i]);
        }
        for (int i = 0; i < now.ids().length; i++) if (now.cpuNs()[i] >= 0) latest.put(now.ids()[i], now.cpuNs()[i]);

        List<long[]> deltas = new ArrayList<>(); // {id, ns}
        long javaSum = 0;
        for (Map.Entry<Long, Long> e : latest.entrySet()) {
            Long b = before.get(e.getKey());
            long d = e.getValue() - (b == null ? 0 : b); // 期間內新建的執行緒從 0 算
            if (d <= 0) continue;
            javaSum += d;
            deltas.add(new long[]{e.getKey(), d});
        }
        long totalNs = javaSum;
        long internalNs = -1;
        if (base.processNs() >= 0 && now.processNs() >= 0) {
            long procNs = now.processNs() - base.processNs();
            internalNs = Math.max(0, procNs - javaSum);
            totalNs = Math.max(procNs, javaSum);
        }
        deltas.sort((a, b) -> Long.compare(b[1], a[1]));

        List<Usage> top = new ArrayList<>();
        int n = Math.min(topN, deltas.size());
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) ids[i] = deltas.get(i)[0];
        ThreadInfo[] infos = n == 0 ? new ThreadInfo[0] : threads.getThreadInfo(ids, 12);
        for (int i = 0; i < n; i++) {
            ThreadInfo info = infos[i];
            String name = info != null ? info.getThreadName() : names.getOrDefault(ids[i], "#" + ids[i]);
            top.add(new Usage(name, info == null ? null : where(info.getStackTrace()), deltas.get(i)[1] / 1_000_000L));
        }
        if (internalNs > 0) top.add(new Usage(JVM_INTERNAL, null, internalNs / 1_000_000L));
        top.sort((a, b) -> Long.compare(b.ms(), a.ms()));
        if (top.size() > topN) top = new ArrayList<>(top.subList(0, topN));

        if (addToTotals) {
            for (Usage u : top) {
                String key = u.name().equals(JVM_INTERNAL) ? JVM_INTERNAL : normalize(u.name());
                if (totals.size() >= MAX_TOTAL_KEYS && !totals.containsKey(key)) continue;
                totals.merge(key, u.ms(), Long::sum);
            }
        }
        return new Report(now.wallMs() - base.wallMs(), totalNs / 1_000_000L, top);
    }

    /** 執行緒目前執行到的第一個插件類別（含 jar 檔名）；沒有則 null */
    private static String where(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            boolean core = false;
            for (String p : CORE_PREFIXES) {
                if (c.startsWith(p)) {
                    core = true;
                    break;
                }
            }
            if (core) continue;
            String jar = e.getClassLoaderName();
            return jar != null && jar.endsWith(".jar") ? jar : c;
        }
        return null;
    }

    /** 名稱中的數字換成 #，讓同一個執行緒池的執行緒合併統計 */
    static String normalize(String name) {
        String n = name.replaceAll("\\d+", "#");
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    /** 累計最多的前 n 項 */
    List<Map.Entry<String, Long>> totals(int n) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(totals.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list.size() > n ? list.subList(0, n) : list;
    }
}
