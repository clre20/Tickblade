package clre20.tickBlade.monitor;

import clre20.tickBlade.TickBlade;
import clre20.tickBlade.config.Settings;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 記錄每個 tick 花費的時間（Paper 的 ServerTickEndEvent），
 * 提供平均 MSPT 給自動降載，並偵測卡頓。
 * 事件處理只做幾個陣列運算，不建立物件。
 */
public final class TickMonitor implements Listener {

    private static final int SIZE = 1200; // 最多保留 60 秒

    private final TickBlade plugin;
    private final double[] ring = new double[SIZE];
    private int index;
    private int filled;
    private final long startNanos = System.nanoTime();

    // 卡頓時間點（毫秒）環狀佇列
    private final long[] spikeTimes = new long[64];
    private int spikeHead;
    private int spikeCount;

    private long totalSpikes;
    private double maxSpike;
    private long lastSpikeAt;
    private double lastSpikeMs;
    private double lastTick;

    public TickMonitor(TickBlade plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent e) {
        double ms = e.getTickDuration();
        lastTick = ms;
        ring[index] = ms;
        index = (index + 1) % SIZE;
        if (filled < SIZE) filled++;

        Settings s = plugin.settings();
        if (!s.spikeEnabled || ms < s.spikeThresholdMs) return;
        if ((System.nanoTime() - startNanos) / 1_000_000_000L < s.startupGraceSeconds) return;
        onSpike(ms, s);
    }

    private void onSpike(double ms, Settings s) {
        long now = System.currentTimeMillis();
        totalSpikes++;
        if (ms > maxSpike) maxSpike = ms;
        lastSpikeAt = now;
        lastSpikeMs = ms;

        spikeTimes[spikeHead] = now;
        spikeHead = (spikeHead + 1) % spikeTimes.length;
        if (spikeCount < spikeTimes.length) spikeCount++;

        int recent = recentSpikes(now, s.triggerWindowSeconds * 1000L);
        plugin.spikeLog().append(now, ms);
        plugin.getLogger().warning(plugin.messages().console("console.spike", "ms", fmt(ms)));
        if (recent >= s.triggerCount && s.autoProfile) {
            if (plugin.sparkCapture().tryAutoStart()) {
                // 開始錄製後清空計數，避免冷卻結束後舊紀錄立刻又觸發
                spikeCount = 0;
            }
        }
    }

    private int recentSpikes(long now, long windowMs) {
        int n = 0;
        for (int i = 0; i < spikeCount; i++) {
            int idx = (spikeHead - 1 - i + spikeTimes.length) % spikeTimes.length;
            if (now - spikeTimes[idx] <= windowMs) n++;
            else break;
        }
        return n;
    }

    /** 最近 ticks 個 tick 的平均 MSPT */
    public double average(int ticks) {
        int n = Math.min(ticks, filled);
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 1; i <= n; i++) sum += ring[(index - i + SIZE) % SIZE];
        return sum / n;
    }

    public int samples() {
        return filled;
    }

    public double lastTick() {
        return lastTick;
    }

    public long totalSpikes() {
        return totalSpikes;
    }

    public double maxSpike() {
        return maxSpike;
    }

    public long lastSpikeAt() {
        return lastSpikeAt;
    }

    public double lastSpikeMs() {
        return lastSpikeMs;
    }

    public static String fmt(double ms) {
        return String.format("%.1f", ms);
    }
}
