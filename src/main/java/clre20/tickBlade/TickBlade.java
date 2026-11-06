package clre20.tickBlade;

import clre20.tickBlade.ai.AiPauser;
import clre20.tickBlade.command.TickBladeCommand;
import clre20.tickBlade.config.Messages;
import clre20.tickBlade.config.Settings;
import clre20.tickBlade.config.YamlFiles;
import clre20.tickBlade.despawn.DespawnFixer;
import clre20.tickBlade.gui.HotspotScanner;
import clre20.tickBlade.gui.MenuListener;
import clre20.tickBlade.monitor.SpikeLog;
import clre20.tickBlade.monitor.StackCapture;
import clre20.tickBlade.monitor.TickMonitor;
import clre20.tickBlade.shed.LoadShedder;
import clre20.tickBlade.spark.SparkCapture;
import clre20.tickBlade.update.UpdateChecker;
import clre20.tickBlade.util.ForeignCache;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TickBlade extends JavaPlugin implements Listener {

    public static final String PERM_HELP = "tickblade.help";
    public static final String PERM_STATUS = "tickblade.status";
    public static final String PERM_HOTSPOTS = "tickblade.hotspots";
    public static final String PERM_INSPECT = "tickblade.inspect";
    public static final String PERM_REPORTS = "tickblade.reports";
    public static final String PERM_PROFILE = "tickblade.profile";
    public static final String PERM_CLEANUP = "tickblade.cleanup";
    public static final String PERM_AI = "tickblade.ai";
    public static final String PERM_RELOAD = "tickblade.reload";
    public static final String PERM_VERSION = "tickblade.version";
    public static final String PERM_NOTIFY = "tickblade.notify";

    private final Messages messages = new Messages();
    private volatile Settings settings;
    private NamespacedKey menuKey;
    private ExecutorService io;
    private UpdateChecker updateChecker;
    private TickMonitor tickMonitor;
    private SpikeLog spikeLog;
    private StackCapture stackCapture;
    private final long enabledAt = System.nanoTime();
    private SparkCapture sparkCapture;
    private LoadShedder loadShedder;
    private DespawnFixer despawnFixer;
    private AiPauser aiPauser;
    private HotspotScanner hotspots;
    private BukkitTask flushTask;
    private final ForeignCache foreignCache = new ForeignCache();

    @Override
    public void onEnable() {
        menuKey = new NamespacedKey(this, "menu");
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TickBlade-IO");
            t.setDaemon(true);
            return t;
        });
        loadFiles();

        updateChecker = new UpdateChecker(getPluginMeta().getVersion());
        tickMonitor = new TickMonitor(this);
        spikeLog = new SpikeLog(this);
        stackCapture = new StackCapture(this);
        sparkCapture = new SparkCapture(this);
        loadShedder = new LoadShedder(this);
        despawnFixer = new DespawnFixer(this);
        aiPauser = new AiPauser(this);
        hotspots = new HotspotScanner(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(tickMonitor, this);
        pm.registerEvents(stackCapture, this);
        pm.registerEvents(loadShedder, this);
        pm.registerEvents(despawnFixer, this);
        pm.registerEvents(aiPauser, this);
        pm.registerEvents(new MenuListener(), this);

        PluginCommand cmd = getCommand("tickblade");
        if (cmd != null) {
            TickBladeCommand executor = new TickBladeCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        startModules();
        // 卡頓紀錄每分鐘批次寫入
        flushTask = getServer().getScheduler().runTaskTimer(this, () -> io.execute(() -> {
            spikeLog.flush();
            stackCapture.flush();
            stackCapture.diagnosis().flush();
        }), 1200L, 1200L);

        getLogger().info(messages.console("console.enabled", "version", getPluginMeta().getVersion()));
        if (settings.updateOnStartup) {
            updateChecker.check().thenAccept(this::logUpdate);
        }
    }

    @Override
    public void onDisable() {
        if (flushTask != null) flushTask.cancel();
        if (stackCapture != null) stackCapture.stop(true);
        if (aiPauser != null) aiPauser.shutdown();
        if (loadShedder != null) loadShedder.stop();
        if (despawnFixer != null) despawnFixer.stop();
        if (hotspots != null) hotspots.shutdown();
        if (sparkCapture != null) sparkCapture.shutdown();
        getServer().getScheduler().cancelTasks(this);
        foreignCache.clear();
        if (io != null) {
            if (spikeLog != null) io.execute(spikeLog::flush);
            if (stackCapture != null) {
                io.execute(stackCapture::flush);
                io.execute(stackCapture.diagnosis()::flush);
            }
            io.shutdown();
            try {
                io.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (updateChecker != null) updateChecker.shutdown();
    }

    private void startModules() {
        stackCapture.start();
        loadShedder.start();
        despawnFixer.start();
        aiPauser.start();
    }

    /** 讀取 config.yml 與 messages.yml；回傳錯誤訊息（null = 成功） */
    private String loadFiles() {
        getDataFolder().mkdirs();
        YamlFiles.Loaded cfg = YamlFiles.load(this, "config.yml", "config-version");
        YamlFiles.Loaded msg = YamlFiles.load(this, "messages.yml", "messages-version");
        messages.load(msg.config(), cfg.config().getString("prefix", ""));
        settings = new Settings(cfg.config(), (section, type) ->
                getLogger().warning(messages.console("console.unknown-type", "section", section, "type", type)));
        String error = null;
        for (var pair : new Object[][]{{"config.yml", cfg}, {"messages.yml", msg}}) {
            String name = (String) pair[0];
            YamlFiles.Loaded l = (YamlFiles.Loaded) pair[1];
            if (l.error() != null) {
                getLogger().severe(messages.console("console.config-error", "file", name, "error", l.error()));
                error = name + ": " + l.error();
            } else if (l.updated()) {
                getLogger().info(messages.console("console.config-updated", "file", name));
            }
        }
        return error;
    }

    /** /tickb reload */
    public String reload() {
        String err = loadFiles();
        foreignCache.clear();
        startModules();
        return err;
    }

    private void logUpdate(UpdateChecker.Result r) {
        String v = updateChecker.currentVersion();
        switch (r.state()) {
            case NEWER -> getLogger().warning(messages.console("console.update-newer", "latest", r.latest(), "version", v, "url", r.url()));
            case LATEST -> getLogger().info(messages.console("console.update-latest", "version", v));
            case DEV -> getLogger().info(messages.console("console.update-dev", "version", v, "latest", r.latest()));
            case FAILED -> getLogger().warning(messages.console("console.update-failed",
                    "reason", messages.console(r.reasonKey(), "code", r.code())));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!settings.updateNotifyJoin || !p.hasPermission(PERM_VERSION)) return;
        UUID id = p.getUniqueId();
        getServer().getScheduler().runTaskLater(this, () -> {
            Player pl = getServer().getPlayer(id);
            if (pl == null) return;
            updateChecker.check().thenAccept(r -> {
                if (r.state() != UpdateChecker.State.NEWER) return;
                getServer().getScheduler().runTask(this, () -> {
                    Player again = getServer().getPlayer(id);
                    if (again != null) messages.send(again, "version.join-notify", "latest", r.latest(),
                            "version", updateChecker.currentVersion(), "url", r.url());
                });
            });
        }, 60L);
    }

    /** 通知有 tickblade.notify 權限的線上玩家（主執行緒） */
    public void notifyAdmins(String key, Object... vars) {
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.hasPermission(PERM_NOTIFY)) messages.send(p, key, vars);
        }
    }

    public Path reportsDir() {
        return getDataFolder().toPath().resolve("reports");
    }

    public ForeignCache foreignCache() {
        return foreignCache;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public NamespacedKey menuKey() {
        return menuKey;
    }

    public ExecutorService io() {
        return io;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    public StackCapture stackCapture() {
        return stackCapture;
    }

    /** 插件啟用後經過的秒數（任何執行緒都可呼叫） */
    public long uptimeSeconds() {
        return (System.nanoTime() - enabledAt) / 1_000_000_000L;
    }

    public SpikeLog spikeLog() {
        return spikeLog;
    }

    public SparkCapture sparkCapture() {
        return sparkCapture;
    }

    public LoadShedder loadShedder() {
        return loadShedder;
    }

    public DespawnFixer despawnFixer() {
        return despawnFixer;
    }

    public AiPauser aiPauser() {
        return aiPauser;
    }

    public HotspotScanner hotspots() {
        return hotspots;
    }
}
