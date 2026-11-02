package clre20.tickBlade.monitor;

import clre20.tickBlade.TickBlade;

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

/**
 * 卡頓紀錄：先放記憶體，每分鐘由背景執行緒批次寫入 reports/spikes.log。
 * 超過大小上限時改名為 spikes.log.1（只保留一份舊檔）。
 */
public final class SpikeLog {

    private static final int MAX_PENDING = 500;

    private final TickBlade plugin;
    private final List<String> pending = new ArrayList<>();

    public SpikeLog(TickBlade plugin) {
        this.plugin = plugin;
    }

    /** 主執行緒呼叫 */
    public void append(long time, double ms) {
        if (!plugin.settings().spikeLogEnabled) return;
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) return;
            pending.add(time + "|" + String.format("%.1f", ms) + "|" + plugin.getServer().getOnlinePlayers().size());
        }
    }

    /** 背景執行緒呼叫（定時與關服） */
    public void flush() {
        List<String> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        for (String line : batch) {
            String[] p = line.split("\\|");
            sb.append(fmt.format(new Date(Long.parseLong(p[0]))))
                    .append("  ").append(p[1]).append(" ms  players=").append(p[2]).append('\n');
        }
        Path dir = plugin.reportsDir();
        Path file = dir.resolve("spikes.log");
        try {
            Files.createDirectories(dir);
            if (Files.exists(file) && Files.size(file) > plugin.settings().spikeLogMaxBytes) {
                Files.move(file, dir.resolve("spikes.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot write spikes.log: " + e.getMessage());
        }
    }
}
