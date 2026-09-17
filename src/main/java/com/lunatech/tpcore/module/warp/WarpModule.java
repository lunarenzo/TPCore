package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.listener.WarpEventListener;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class WarpModule implements ReloadableModule {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private volatile WarpConfig config;
    private boolean isInitialized = false;

    private WarpRepository repository;
    private WarpCache cache;
    private WarpService service;
    private WarpEventListener eventListener;
    private WarpCommandRegistry commandRegistry;

    public WarpModule(JavaPlugin plugin, ModularConfigManager configManager, WarpConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    @Override
    public String getModuleName() {
        return "warp";
    }

    @Override
    public boolean reloadConfig() {
        try {
            WarpConfig newConfig = this.configManager.tryLoadModuleConfig("warp", WarpConfig.class, WarpConfig.createDefault());
            if (newConfig != null) {
                boolean wasEnabled = this.isInitialized;
                boolean isEnabled = newConfig.enabled();
                this.config = newConfig;

                if (wasEnabled && !isEnabled) {
                    disable();
                } else if (!wasEnabled && isEnabled) {
                    enable();
                } else if (wasEnabled && isEnabled) {
                    disable();
                    enable();
                }
                return true;
            }
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Failed to reload Warp module configuration.", e);
        }
        return false;
    }

    public void enable() {
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("Warp Module is disabled in configuration. Skipping registration.");
            return;
        }

        if (this.isInitialized) {
            return;
        }

        if (this.config.storage() != null && "YAML".equalsIgnoreCase(this.config.storage().type())) {
            this.repository = new YamlWarpRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger());
        } else {
            this.repository = new SqliteWarpRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger());
        }

        this.cache = new DefaultWarpCache();
        this.service = new DefaultWarpService(this.plugin, this.repository, this.cache, this.config, this.plugin.getSLF4JLogger());
        this.service.initialize().join();

        this.eventListener = new WarpEventListener(this.service, () -> this.config);
        this.plugin.getServer().getPluginManager().registerEvents(this.eventListener, this.plugin);

        this.commandRegistry = new WarpCommandRegistry(this.plugin, this.service, () -> this.config);
        this.commandRegistry.registerAll();

        this.configManager.registerModule(this);
        this.isInitialized = true;

        this.plugin.getSLF4JLogger().info("Warp Module successfully enabled.");
    }

    public void disable() {
        if (!this.isInitialized) {
            return;
        }

        this.configManager.unregisterModule("warp");

        if (this.eventListener != null) {
            HandlerList.unregisterAll(this.eventListener);
        }

        if (this.service != null) {
            this.service.close().join();
        }

        this.isInitialized = false;
        this.plugin.getSLF4JLogger().info("Warp Module disabled.");
    }

    public WarpService getService() {
        return this.service;
    }

    public WarpCache getCache() {
        return this.cache;
    }
}
