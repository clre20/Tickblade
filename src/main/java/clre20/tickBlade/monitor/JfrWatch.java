package clre20.tickBlade.monitor;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用 Java 內建的 JFR 記錄 JVM 安全點（safepoint）：安全點期間所有 Java 執行緒都會停住。
 * 一次安全點的暫停 = 等所有執行緒停下（jdk.SafepointStateSynchronization）＋ 執行 VM 操作（jdk.ExecuteVMOperation）。
 * （實測 jdk.SafepointBegin 的長度不含 VM 操作，所以不用它。）
 * 只啟用兩種事件，由 JFR 自己的一條執行緒處理；記憶體只保留最近的資料。
 * 只在這個類別引用 jdk.jfr，執行環境沒有 JFR 時由呼叫端捕捉錯誤後停用。
 */
final class JfrWatch {

    /** 一次較長的安全點（牆鐘時間，毫秒） */
    record Safepoint(long startMs, long endMs, long id) {
    }

    record Info(String operation, long syncMs, long opMs) {
        long totalMs() {
            return Math.max(0, syncMs) + Math.max(0, opMs);
        }
    }

    private static final int MAX_SAFEPOINTS = 200;
    private static final int MAX_IDS = 1024;

    private final RecordingStream stream;
    private final long thresholdMs;
    private final ArrayDeque<Safepoint> safepoints = new ArrayDeque<>();
    private final Map<Long, String> opNames = bounded();
    private final Map<Long, Long> opMs = bounded();
    private final Map<Long, Long> syncMs = bounded();

    private static <V> Map<Long, V> bounded() {
        return new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> e) {
                return size() > MAX_IDS;
            }
        };
    }

    JfrWatch(int safepointThresholdMs) {
        thresholdMs = safepointThresholdMs;
        stream = new RecordingStream();
        stream.setMaxAge(Duration.ofMinutes(2));
        stream.setMaxSize(16L * 1024 * 1024);
        // 全部收（才能把同一次安全點的兩段對起來），只保留超過門檻的
        stream.enable("jdk.ExecuteVMOperation").withThreshold(Duration.ZERO);
        stream.enable("jdk.SafepointStateSynchronization").withThreshold(Duration.ZERO);
        stream.onEvent("jdk.ExecuteVMOperation", this::onOperation);
        stream.onEvent("jdk.SafepointStateSynchronization", this::onSync);
    }

    void start() {
        stream.startAsync();
    }

    void close() {
        stream.close();
    }

    private static long safepointId(RecordedEvent e) {
        try {
            return e.getLong("safepointId");
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private void onOperation(RecordedEvent e) {
        long id = safepointId(e);
        boolean atSafepoint;
        String op;
        try {
            atSafepoint = e.getBoolean("safepoint");
            op = e.getString("operation");
        } catch (RuntimeException ex) {
            return;
        }
        if (!atSafepoint || id < 0) return;
        long ms = e.getDuration().toMillis();
        synchronized (this) {
            opNames.put(id, op);
            opMs.put(id, ms);
            if (ms >= thresholdMs || ms + syncMs.getOrDefault(id, 0L) >= thresholdMs) {
                add(new Safepoint(e.getStartTime().toEpochMilli(), e.getEndTime().toEpochMilli(), id));
            }
        }
    }

    private void onSync(RecordedEvent e) {
        long id = safepointId(e);
        if (id < 0) return;
        long ms = e.getDuration().toMillis();
        synchronized (this) {
            syncMs.put(id, ms);
            if (ms >= thresholdMs || ms + opMs.getOrDefault(id, 0L) >= thresholdMs) {
                add(new Safepoint(e.getStartTime().toEpochMilli(), e.getEndTime().toEpochMilli(), id));
            }
        }
    }

    private void add(Safepoint sp) {
        if (safepoints.size() >= MAX_SAFEPOINTS) safepoints.pollFirst();
        safepoints.addLast(sp);
    }

    /** 和 [fromMs, toMs] 重疊的較長安全點中，總暫停最長的那一次（沒有則 null） */
    synchronized Info longestOverlapping(long fromMs, long toMs) {
        Info best = null;
        for (Safepoint sp : safepoints) {
            if (sp.endMs() < fromMs || sp.startMs() > toMs) continue;
            Info i = new Info(opNames.getOrDefault(sp.id(), "?"),
                    syncMs.getOrDefault(sp.id(), -1L), opMs.getOrDefault(sp.id(), -1L));
            if (best == null || i.totalMs() > best.totalMs()) best = i;
        }
        return best;
    }
}
