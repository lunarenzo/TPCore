package com.lunatech.tpcore.module.pwarp;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.cache.impl.DefaultPwarpCache;
import com.lunatech.tpcore.module.pwarp.command.PwarpCommandRegistry;
import com.lunatech.tpcore.module.pwarp.config.PwarpConfig;
import com.lunatech.tpcore.module.pwarp.gui.PwarpGuiManager;
import com.lunatech.tpcore.module.pwarp.listener.PwarpEventListener;
import com.lunatech.tpcore.module.pwarp.repository.PwarpAccessRepository;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRepository;
import com.lunatech.tpcore.module.pwarp.repository.impl.SqlitePwarpAccessRepository;
import com.lunatech.tpcore.module.pwarp.repository.impl.SqlitePwarpRepository;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import com.lunatech.tpcore.module.pwarp.service.impl.DefaultPwarpService;
import com.lunatech.tpcore.module.pwarp.util.PwarpInputManager;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Composition root and lifecycle manager for the PlayerWarps module.
 */
public final class PwarpModule implements ReloadableModule {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private volatile PwarpConfig config;
    private boolean isInitialized = false;
    private boolean commandsRegistered = false;

    private PwarpRepository repository;
    private PwarpAccessRepository accessRepository;
    private PwarpInputManager inputManager;
    private PwarpCache cache;
    private PwarpService service;
    private PwarpGuiManager guiManager;
    private PwarpEventListener eventListener;
    private PwarpCommandRegistry commandRegistry;

    public PwarpModule(JavaPlugin plugin, ModularConfigManager configManager, PwarpConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    @Override
    public String getModuleName() {
        return "pwarp";
    }

    @Override
    public boolean reloadConfig() {
        try {
            PwarpConfig newConfig = this.configManager.tryLoadModuleConfig("pwarp", PwarpConfig.class, PwarpConfig.createDefault());
            if (newConfig != null) {
                boolean wasEnabled = this.isInitialized;
                boolean isEnabled = newConfig.enabled();
                this.config = newConfig;

                if (wasEnabled && !isEnabled) {
                    disable();
                } else if (!wasEnabled && isEnabled) {
                    enable();
                }
                return true;
            }
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Failed to reload PlayerWarps module configuration.", e);
        }
        return false;
    }

    public void enable() {
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("PlayerWarps Module is disabled in configuration. Skipping registration.");
            return;
        }

        if (this.isInitialized) {
            return;
        }

        this.repository = new SqlitePwarpRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger());
        this.accessRepository = new SqlitePwarpAccessRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger());
        this.accessRepository.initialize().join();

        this.inputManager = new PwarpInputManager(this.plugin);
        this.plugin.getServer().getPluginManager().registerEvents(this.inputManager, this.plugin);

        this.cache = new DefaultPwarpCache();
        this.service = new DefaultPwarpService(this.plugin, () -> this.config, this.repository, this.cache);
        this.service.initialize().join();

        this.guiManager = new PwarpGuiManager(this.plugin, this.service, this.accessRepository, this.inputManager, () -> this.config);
        this.plugin.getServer().getPluginManager().registerEvents(this.guiManager, this.plugin);

        this.eventListener = new PwarpEventListener(() -> this.service);
        this.plugin.getServer().getPluginManager().registerEvents(this.eventListener, this.plugin);

        if (!this.commandsRegistered) {
            this.commandRegistry = new PwarpCommandRegistry(
                this.plugin,
                this.service,
                this.guiManager,
                () -> this.config,
                this::reloadConfig
            );
            this.commandRegistry.registerAll();
            this.commandsRegistered = true;
        }

        this.configManager.registerModule(this);
        this.isInitialized = true;

        this.plugin.getSLF4JLogger().info("PlayerWarps Module successfully enabled.");
    }

    public void disable() {
        if (!this.isInitialized) {
            return;
        }

        this.configManager.unregisterModule("pwarp");

        if (this.inputManager != null) {
            HandlerList.unregisterAll(this.inputManager);
            this.inputManager = null;
        }

        if (this.accessRepository != null) {
            this.accessRepository.close();
            this.accessRepository = null;
        }

        if (this.guiManager != null) {
            HandlerList.unregisterAll(this.guiManager);
            this.guiManager = null;
        }

        if (this.eventListener != null) {
            HandlerList.unregisterAll(this.eventListener);
            this.eventListener = null;
        }

        if (this.service != null) {
            this.service.shutdown();
            this.service = null;
        }

        this.isInitialized = false;
        this.plugin.getSLF4JLogger().info("PlayerWarps Module disabled.");
    }

    public PwarpService getService() {
        return this.service;
    }

    public PwarpCache getCache() {
        return this.cache;
    }

    public PwarpGuiManager getGuiManager() {
        return this.guiManager;
    }
}
