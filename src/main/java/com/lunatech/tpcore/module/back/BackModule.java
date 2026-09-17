package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.cache.BackCache;
import com.lunatech.tpcore.module.back.cache.impl.DefaultBackCache;
import com.lunatech.tpcore.module.back.command.BackCommandRegistry;
import com.lunatech.tpcore.module.back.listener.BackEventListener;
import com.lunatech.tpcore.module.back.repository.BackRepository;
import com.lunatech.tpcore.module.back.repository.impl.SqliteBackRepository;
import com.lunatech.tpcore.module.back.repository.impl.YamlBackRepository;
import com.lunatech.tpcore.module.back.service.BackService;
import com.lunatech.tpcore.module.back.service.impl.DefaultBackService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class BackModule implements ReloadableModule {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private volatile BackConfig config;
    private boolean isInitialized = false;
    private boolean commandsRegistered = false;

    private BackRepository repository;
    private BackCache cache;
    private BackService service;
    private BackEventListener eventListener;
    private BackCommandRegistry commandRegistry;

    public BackModule(JavaPlugin plugin, ModularConfigManager configManager, BackConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    @Override
    public String getModuleName() {
        return "back";
    }

    @Override
    public boolean supportsMigration() {
        return true;
    }

    @Override
    public CompletableFuture<Integer> migrateData(String fromStorage, String toStorage) {
        if (this.service == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Back service is not initialized."));
        }
        return this.service.migrateData(fromStorage, toStorage);
    }

    @Override
    public boolean reloadConfig() {
        try {
            BackConfig newConfig = this.configManager.tryLoadModuleConfig("back", BackConfig.class, BackConfig.createDefault());
            if (newConfig != null) {
                boolean wasEnabled = this.isInitialized;
                boolean isEnabled = newConfig.enabled();
                this.config = newConfig;

                if (wasEnabled && !isEnabled) {
                    disable();
                } else if (!wasEnabled && isEnabled) {
                    enable();
                } else if (wasEnabled && isEnabled) {
                    reloadActiveModule(newConfig);
                }
                return true;
            }
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Failed to reload Back module configuration.", e);
        }
        return false;
    }

    public void enable() {
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("Back Module is disabled in configuration. Skipping registration.");
            return;
        }

        if (this.isInitialized) {
            return;
        }

        instantiateAndInitializeStorage(this.config);

        if (this.eventListener == null) {
            this.eventListener = new BackEventListener(this::getService, () -> this.config);
            this.plugin.getServer().getPluginManager().registerEvents(this.eventListener, this.plugin);
        }

        if (!this.commandsRegistered) {
            this.commandRegistry = new BackCommandRegistry(this.plugin, this::getService, () -> this.config);
            this.commandRegistry.registerAll();
            this.commandsRegistered = true;
        }

        this.configManager.registerModule(this);
        this.isInitialized = true;

        this.plugin.getSLF4JLogger().info("Back Module successfully enabled.");
    }

    private void reloadActiveModule(BackConfig newConfig) {
        String oldStorage = (this.config != null && this.config.storage() != null) ? this.config.storage().type() : "SQLITE";
        String newStorage = (newConfig != null && newConfig.storage() != null) ? newConfig.storage().type() : "SQLITE";

        if (oldStorage.equalsIgnoreCase(newStorage) && this.service != null) {
            this.service.updateConfig(newConfig);
            this.plugin.getSLF4JLogger().info("Back Module configuration reloaded successfully.");
            return;
        }

        if (this.service != null) {
            this.service.close().join();
        }

        instantiateAndInitializeStorage(newConfig);
        this.plugin.getSLF4JLogger().info("Back Module storage engine successfully reloaded: {}", newStorage);
    }

    private void instantiateAndInitializeStorage(BackConfig targetConfig) {
        if (targetConfig.storage() != null && "YAML".equalsIgnoreCase(targetConfig.storage().type())) {
            this.repository = new YamlBackRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger());
        } else {
            this.repository = new SqliteBackRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger());
        }

        this.cache = new DefaultBackCache();
        this.service = new DefaultBackService(this.plugin, this.repository, this.cache, targetConfig, this.plugin.getSLF4JLogger());
        this.service.initialize().join();
    }

    public void disable() {
        if (!this.isInitialized) {
            return;
        }

        this.configManager.unregisterModule("back");

        if (this.eventListener != null) {
            HandlerList.unregisterAll(this.eventListener);
            this.eventListener = null;
        }

        if (this.service != null) {
            this.service.close().join();
            this.service = null;
        }

        this.isInitialized = false;
        this.plugin.getSLF4JLogger().info("Back Module disabled.");
    }

    public BackService getService() {
        return this.service;
    }

    public BackCache getCache() {
        return this.cache;
    }
}
