package clre20.tickBlade.config;

import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/** config.yml 解析後的設定，所有值都有預設值與範圍限制 */
public final class Settings {

    public record ShedLevel(double mspt, int simulationDistance, int viewDistance) {
    }

    public record TimeWindow(int from, int to) {
        public boolean contains(long dayTime) {
            return from <= to ? dayTime >= from && dayTime < to : dayTime >= from || dayTime < to;
        }
    }

    public final String prefix;
    public final boolean updateOnStartup;
    public final boolean updateNotifyJoin;

    // 保護規則
    public final String nameKeyword;
    public final boolean skipScoreboardTags;
    public final boolean skipForeignPdc;
    public final List<String> metadataKeys;

    // A
    public final boolean despawnEnabled;
    public final Set<String> despawnWorlds;
    public final boolean blockEndermanPickup;
    public final boolean fixOnChunkLoad;
    public final int sweepIntervalMinutes;
    public final int despawnMaxPerTick;
    public final boolean despawnSkipConfined;
    public final Set<EntityType> despawnExcluded;

    // B
    public final boolean spikeEnabled;
    public final double spikeThresholdMs;
    public final int triggerCount;
    public final int triggerWindowSeconds;
    public final int startupGraceSeconds;
    public final boolean autoProfile;
    public final int onlyTicksOverMs;
    public final int profileDurationSeconds;
    public final int profileCooldownMinutes;
    public final int reportsMaxFiles;
    public final long reportsMaxBytes;
    public final boolean spikeLogEnabled;
    public final long spikeLogMaxBytes;
    public final int hotspotCacheSeconds;
    public final int hotspotChunksPerTick;
    public final int hotspotMinOpLevel;

    // C
    public final boolean shedEnabled;
    public final int shedAverageSeconds;
    public final int shedMinSecondsBetween;
    public final double shedRecoverBelow;
    public final int shedRecoverAfterSeconds;
    public final List<ShedLevel> shedLevels;
    public final boolean shedNotify;

    // D
    public final boolean aiEnabled;
    public final Set<String> aiWorlds;
    public final int aiScanIntervalSeconds;
    public final double aiMaxMsPerTick;
    public final double crowdRadius;
    public final int crowdPauseAt;
    public final int crowdResumeAt;
    public final boolean aiConfined;
    public final double hostileTargetRadius;
    public final double golemFarmRadius;
    public final double villagerThreatRadius;
    public final boolean aiSkipTamed;
    public final Set<EntityType> aiExcluded;
    public final List<TimeWindow> villagerWindows;
    public final int wakeAfterTradeTicks;
    public final int wakeWhenDamagedTicks;
    public final int wakeOnInteractTicks;
    public final int wakeWorkstationTicks;
    public final double workstationWakeRadius;
    public final boolean wakeShearedSheep;
    public final boolean wakeLoveMode;

    public Settings(YamlConfiguration c, BiConsumer<String, String> unknownType) {
        prefix = c.getString("prefix", "&8[&cTickBlade&8] &r");
        updateOnStartup = c.getBoolean("update-checker.check-on-startup", true);
        updateNotifyJoin = c.getBoolean("update-checker.notify-on-join", true);

        nameKeyword = c.getString("protection.name-keyword", "保留");
        skipScoreboardTags = c.getBoolean("protection.skip-scoreboard-tags", true);
        skipForeignPdc = c.getBoolean("protection.skip-foreign-pdc", true);
        metadataKeys = List.copyOf(c.getStringList("protection.metadata-keys"));

        despawnEnabled = c.getBoolean("despawn-fix.enabled", true);
        despawnWorlds = lowerSet(c.getStringList("despawn-fix.worlds"));
        blockEndermanPickup = c.getBoolean("despawn-fix.block-enderman-pickup", true);
        fixOnChunkLoad = c.getBoolean("despawn-fix.fix-on-chunk-load", true);
        sweepIntervalMinutes = clamp(c.getInt("despawn-fix.sweep-interval-minutes", 5), 0, 1440);
        despawnMaxPerTick = clamp(c.getInt("despawn-fix.max-per-tick", 40), 1, 1000);
        despawnSkipConfined = c.getBoolean("despawn-fix.skip-confined", true);
        despawnExcluded = types(c.getStringList("despawn-fix.excluded-types"), "despawn-fix.excluded-types", unknownType);

        spikeEnabled = c.getBoolean("spike-detector.enabled", true);
        spikeThresholdMs = clamp(c.getDouble("spike-detector.spike-threshold-ms", 500), 60, 60000);
        triggerCount = clamp(c.getInt("spike-detector.trigger-count", 2), 1, 1000);
        triggerWindowSeconds = clamp(c.getInt("spike-detector.trigger-window-seconds", 300), 1, 86400);
        startupGraceSeconds = clamp(c.getInt("spike-detector.startup-grace-seconds", 180), 0, 3600);
        autoProfile = c.getBoolean("spike-detector.auto-profile.enabled", true);
        onlyTicksOverMs = clamp(c.getInt("spike-detector.auto-profile.only-ticks-over-ms", 100), 50, 60000);
        profileDurationSeconds = clamp(c.getInt("spike-detector.auto-profile.duration-seconds", 300), 30, 3600);
        profileCooldownMinutes = clamp(c.getInt("spike-detector.auto-profile.cooldown-minutes", 30), 0, 10080);
        reportsMaxFiles = clamp(c.getInt("spike-detector.reports.max-files", 20), 1, 1000);
        reportsMaxBytes = clamp(c.getLong("spike-detector.reports.max-total-mb", 500), 1, 100_000) * 1024L * 1024L;
        spikeLogEnabled = c.getBoolean("spike-detector.spike-log.enabled", true);
        spikeLogMaxBytes = clamp(c.getLong("spike-detector.spike-log.max-size-kb", 1024), 16, 102_400) * 1024L;
        hotspotCacheSeconds = clamp(c.getInt("hotspots.cache-seconds", 30), 0, 3600);
        hotspotChunksPerTick = clamp(c.getInt("hotspots.chunks-per-tick", 150), 10, 5000);
        hotspotMinOpLevel = clamp(c.getInt("hotspots.min-op-level", 2), 0, 4);

        shedEnabled = c.getBoolean("load-shedding.enabled", true);
        shedAverageSeconds = clamp(c.getInt("load-shedding.average-seconds", 10), 1, 60);
        shedMinSecondsBetween = clamp(c.getInt("load-shedding.min-seconds-between-changes", 15), 1, 3600);
        shedRecoverBelow = clamp(c.getDouble("load-shedding.recover-below-mspt", 35), 1, 1000);
        shedRecoverAfterSeconds = clamp(c.getInt("load-shedding.recover-after-seconds", 60), 1, 3600);
        shedLevels = shedLevels(c);
        shedNotify = c.getBoolean("load-shedding.notify", true);

        aiEnabled = c.getBoolean("ai-pause.enabled", true);
        aiWorlds = lowerSet(c.getStringList("ai-pause.worlds"));
        aiScanIntervalSeconds = clamp(c.getInt("ai-pause.scan-interval-seconds", 5), 1, 600);
        aiMaxMsPerTick = clamp(c.getDouble("ai-pause.max-ms-per-tick", 1.0), 0.1, 20);
        crowdRadius = clamp(c.getDouble("ai-pause.crowd.radius", 1.5), 0.5, 4);
        int pauseAt = clamp(c.getInt("ai-pause.crowd.pause-at", 5), 2, 1000);
        crowdPauseAt = pauseAt;
        crowdResumeAt = clamp(c.getInt("ai-pause.crowd.resume-at", 3), 1, pauseAt - 1);
        aiConfined = c.getBoolean("ai-pause.confined", true);
        hostileTargetRadius = clamp(c.getDouble("ai-pause.hostile-target-radius", 16), 0, 64);
        golemFarmRadius = clamp(c.getDouble("ai-pause.golem-farm-radius", 10), 0, 32);
        villagerThreatRadius = clamp(c.getDouble("ai-pause.wake.villager-threat-radius", 12), 0, 32);
        aiSkipTamed = c.getBoolean("ai-pause.skip-tamed", true);
        aiExcluded = types(c.getStringList("ai-pause.excluded-types"), "ai-pause.excluded-types", unknownType);
        villagerWindows = windows(c.getStringList("ai-pause.wake.villager-work-windows"));
        wakeAfterTradeTicks = clamp(c.getInt("ai-pause.wake.villager-after-trade-seconds", 10), 0, 3600) * 20;
        wakeWhenDamagedTicks = clamp(c.getInt("ai-pause.wake.when-damaged-seconds", 10), 0, 3600) * 20;
        wakeOnInteractTicks = clamp(c.getInt("ai-pause.wake.villager-interact-seconds", 10), 0, 3600) * 20;
        wakeWorkstationTicks = clamp(c.getInt("ai-pause.wake.workstation-placed-seconds", 60), 0, 3600) * 20;
        workstationWakeRadius = clamp(c.getDouble("ai-pause.wake.workstation-placed-radius", 16), 1, 48);
        wakeShearedSheep = c.getBoolean("ai-pause.wake.sheared-sheep", true);
        wakeLoveMode = c.getBoolean("ai-pause.wake.love-mode", true);
    }

    public boolean despawnWorld(String world) {
        return despawnWorlds.isEmpty() || despawnWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    public boolean aiWorld(String world) {
        return aiWorlds.isEmpty() || aiWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    private static List<ShedLevel> shedLevels(YamlConfiguration c) {
        List<ShedLevel> out = new ArrayList<>();
        for (var map : c.getMapList("load-shedding.levels")) {
            double mspt = num(map.get("mspt"), -1);
            if (mspt <= 0) continue;
            int sim = (int) num(map.get("simulation-distance"), 0);
            int view = (int) num(map.get("view-distance"), 0);
            sim = sim <= 0 ? 0 : clamp(sim, 2, 32);
            view = view <= 0 ? 0 : clamp(view, 2, 32);
            out.add(new ShedLevel(mspt, sim, view));
        }
        out.sort((a, b) -> Double.compare(a.mspt(), b.mspt()));
        return Collections.unmodifiableList(out);
    }

    private static List<TimeWindow> windows(List<String> raw) {
        List<TimeWindow> out = new ArrayList<>();
        for (String s : raw) {
            String[] p = s.trim().split("-");
            if (p.length != 2) continue;
            try {
                int a = clamp(Integer.parseInt(p[0].trim()), 0, 23999);
                int b = clamp(Integer.parseInt(p[1].trim()), 0, 24000);
                out.add(new TimeWindow(a, b));
            } catch (NumberFormatException ignored) {
            }
        }
        return List.copyOf(out);
    }

    private static Set<EntityType> types(List<String> raw, String section, BiConsumer<String, String> unknown) {
        Set<EntityType> out = EnumSet.noneOf(EntityType.class);
        for (String s : raw) {
            String name = s.trim().toLowerCase(Locale.ROOT);
            if (name.startsWith("minecraft:")) name = name.substring(10);
            NamespacedKey key = name.matches("[a-z0-9_./-]+") ? NamespacedKey.minecraft(name) : null;
            EntityType t = key == null ? null : Registry.ENTITY_TYPE.get(key);
            if (t == null) unknown.accept(section, s);
            else out.add(t);
        }
        return out;
    }

    private static Set<String> lowerSet(List<String> raw) {
        Set<String> out = new HashSet<>();
        for (String s : raw) out.add(s.trim().toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(out);
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }
}
