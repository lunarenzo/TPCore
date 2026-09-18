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
import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Composition root for the Quantum-RTP module.
 * Wires together candidate caching, Anvil prefiltering,
 * roaring bitset indexing, adaptive queue replenishment, and Brigadier commands.
 */
public final class RtpModule implements ReloadableModule {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private final RtpConfigManager rtpConfigManager;

    private volatile RtpConfig config;
    private boolean isInitialized = false;
    private boolean commandsRegistered = false;

    private RtpCacheRepository repository;
    private RoaringRegionIndex roaringIndex;
    private RtpSafetyInspector safetyInspector;
    private RtpChunkTicketManager ticketManager;
    private AdaptiveRtpReplenisher replenisher;
    private RtpService service;
    private RtpPredictiveJoinListener eventListener;
    private RtpCommandRegistry commandRegistry;

    public RtpModule(JavaPlugin plugin, ModularConfigManager configManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.rtpConfigManager = new RtpConfigManager(configManager, plugin.getSLF4JLogger());
    }

    @Override
    public String getModuleName() {
        return "rtp";
    }

    @Override
    public boolean reloadConfig() {
        try {
            RtpConfig newConfig = this.rtpConfigManager.load();
            if (newConfig != null) {
                boolean wasEnabled = this.isInitialized;
                boolean isEnabled = newConfig.enabled();
                this.config = newConfig;

                if (wasEnabled && !isEnabled) {
                    disable();
                } else if (!wasEnabled && isEnabled) {
                    enable();
                } else if (wasEnabled && isEnabled) {
                    if (this.service != null) {
                        this.service.triggerReplenishment();
                    }
                }
                return true;
            }
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Failed to reload RTP module configuration.", e);
        }
        return false;
    }

    public void enable() {
        this.config = this.rtpConfigManager.load();

        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("RTP Module is disabled in configuration. Skipping registration.");
            return;
        }

        if (this.isInitialized) {
            return;
        }

        File dbFile = new File(this.plugin.getDataFolder(), "rtp_cache.db");
        this.repository = new SqliteRtpCacheRepository(dbFile, this.plugin.getSLF4JLogger());
        this.repository.initialize().join();

        this.roaringIndex = new RoaringRegionIndex();
        this.safetyInspector = new RtpSafetyInspector(this.plugin.getDataFolder().toPath());
        this.ticketManager = new RtpChunkTicketManager(this.plugin);

        this.replenisher = new AdaptiveRtpReplenisher(
            this.plugin,
            this.rtpConfigManager,
            this.safetyInspector,
            this.repository,
            this.roaringIndex,
            this.plugin.getSLF4JLogger()
        );
        this.replenisher.start();

        this.service = new DefaultRtpService(
            this.plugin,
            () -> this.config,
            this.replenisher,
            this.safetyInspector,
            this.ticketManager
        );

        // Register predictive join listener
        if (this.eventListener == null) {
            this.eventListener = new RtpPredictiveJoinListener(this::getService);
            this.plugin.getServer().getPluginManager().registerEvents(this.eventListener, this.plugin);
        }

        // Register Brigadier commands
        if (!this.commandsRegistered) {
            this.commandRegistry = new RtpCommandRegistry(this.plugin, this::getService, () -> this.config, this::reloadConfig);
            this.commandRegistry.registerAll();
            this.commandsRegistered = true;
        }

        this.configManager.registerModule(this);
        this.isInitialized = true;

        this.plugin.getSLF4JLogger().info("Quantum-RTP Module successfully enabled.");
    }

    public void disable() {
        if (!this.isInitialized) {
            return;
        }

        this.configManager.unregisterModule("rtp");

        if (this.eventListener != null) {
            HandlerList.unregisterAll(this.eventListener);
            this.eventListener = null;
        }

        if (this.replenisher != null) {
            this.replenisher.stop();
            this.replenisher = null;
        }

        if (this.service != null) {
            this.service.shutdown();
            this.service = null;
        }

        if (this.repository != null) {
            this.repository.close().join();
            this.repository = null;
        }

        this.isInitialized = false;
        this.plugin.getSLF4JLogger().info("Quantum-RTP Module disabled.");
    }

    public RtpService getService() {
        return this.service;
    }
}
