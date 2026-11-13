package com.lunatech.tpcore.module.rtp;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.module.rtp.command.RtpCommandRegistry;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpConfigManager;
import com.lunatech.tpcore.module.rtp.index.RoaringRegionIndex;
import com.lunatech.tpcore.module.rtp.listener.RtpPredictiveJoinListener;
import com.lunatech.tpcore.module.rtp.repository.RtpCacheRepository;
import com.lunatech.tpcore.module.rtp.repository.impl.SqliteRtpCacheRepository;
import com.lunatech.tpcore.module.rtp.service.RtpService;
import com.lunatech.tpcore.module.rtp.service.impl.AdaptiveRtpReplenisher;
import com.lunatech.tpcore.module.rtp.service.impl.DefaultRtpService;
import com.lunatech.tpcore.module.rtp.service.impl.RtpChunkTicketManager;
import com.lunatech.tpcore.module.rtp.service.impl.RtpSafetyInspector;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class RtpModule implements ReloadableModule {
    private final JavaPlugin plugin;
    private final RtpConfigManager configs;
    // Survives configuration restarts: unresolved native work never gains a new quota through reload.
    private final RtpSafetyInspector inspector;
    private volatile RtpConfig config = RtpConfig.createDefault();
    private volatile RtpService service;
    private RtpCacheRepository repository;
    private AdaptiveRtpReplenisher replenisher;
    private final RtpChunkTicketManager tickets;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private RtpPredictiveJoinListener listener;
    private CompletableFuture<Void> stopped = CompletableFuture.completedFuture(null);
    private long generation;
    private boolean active, registered;

    public RtpModule(JavaPlugin plugin, ModularConfigManager manager) {
        this.plugin = plugin;
        configs = new RtpConfigManager(manager, plugin.getSLF4JLogger());
        inspector = new RtpSafetyInspector(plugin);
        tickets = new RtpChunkTicketManager(plugin);
        manager.registerModule(this);
    }
    @Override public String getModuleName() { return "rtp"; }
    @Override public boolean reloadConfig() {
        var next = configs.load();
        if (next == null) return false;
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                if (!active) return;
                config = next;
                restart();
            });
            return true;
        } catch (RuntimeException rejected) { return false; }
    }
    public void enable() {
        if (active) return;
        active = true;
        if (!registered) {
            new RtpCommandRegistry(plugin, this::getService, () -> config, this::reloadConfig).registerAll();
            registered = true;
        }
        long epoch = ++generation;
        Thread.ofVirtual().name("tpcore-rtp-config-start").start(() -> {
            var loaded = configs.load();
            global(() -> {
                if (active && epoch == generation && loaded != null) { config = loaded; restart(); }
            });
        });
    }
    private void restart() {
        long epoch = ++generation;
        stop();
        stopped.whenComplete((ignored, failure) -> global(() -> {
            if (!active || epoch != generation || !config.enabled() || failure != null) return;
            var runtime = config.runtime();
            var opened = new SqliteRtpCacheRepository(new File(plugin.getDataFolder(), "rtp_cache.db"), plugin.getSLF4JLogger(),
                runtime.storageQueueCapacity(), runtime.cacheRetentionPerWorld(), runtime.cacheMaxAgeMinutes());
            repository = opened;
            opened.initialize().whenComplete((ready, error) -> global(() -> {
                if (error != null) { plugin.getSLF4JLogger().error("RTP storage initialization failed", error); opened.close(); return; }
                if (!active || epoch != generation) { opened.close(); return; }
                replenisher = new AdaptiveRtpReplenisher(plugin, configs, inspector, opened, new RoaringRegionIndex(), plugin.getSLF4JLogger());
                service = new DefaultRtpService(plugin, () -> config, replenisher, inspector, tickets, cooldowns);
                listener = new RtpPredictiveJoinListener(this::getService);
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
                replenisher.start();
            }));
        }));
    }
    private void global(Runnable action) {
        try { Bukkit.getGlobalRegionScheduler().execute(plugin, action); }
        catch (RuntimeException retired) { plugin.getSLF4JLogger().warn("RTP lifecycle callback could not be scheduled", retired); }
    }
    private void stop() {
        if (service != null) { service.shutdown(); service = null; }
        if (listener != null) { HandlerList.unregisterAll(listener); listener = null; }
        if (replenisher != null) { replenisher.stop(); replenisher = null; }
        tickets.clear();
        if (repository != null) { stopped = repository.close(); repository = null; }
    }
    public void disable() { active = false; generation++; stop(); tickets.close(); cooldowns.clear(); }
    public RtpService getService() { return service; }
}
