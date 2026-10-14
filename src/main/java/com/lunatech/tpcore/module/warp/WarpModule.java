package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.impl.VaultWarpEconomyService;
import com.lunatech.tpcore.module.warp.listener.WarpEventListener;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.HandlerList;
import org.bukkit.Bukkit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.plugin.java.JavaPlugin;

public final class WarpModule implements ReloadableModule {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private volatile WarpConfig config;
    private boolean isInitialized = false;
    private boolean commandsRegistered = false;

    private WarpRepository repository;
    private WarpCache cache;
    private WarpEconomyService economyService;
    private volatile WarpService service;
    private CompletableFuture<Void> stopping = CompletableFuture.completedFuture(null);
    private final AtomicLong lifecycle = new AtomicLong();
    private WarpEventListener eventListener;
    private WarpCommandRegistry commandRegistry;

    public WarpModule(JavaPlugin plugin, ModularConfigManager configManager, WarpConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.configManager.registerModule(this);
    }

    @Override
    public String getModuleName() {
        return "warp";
    }

    @Override
    public boolean supportsMigration() {
        return true;
    }

    @Override
    public CompletableFuture<Integer> migrateData(String fromStorage, String toStorage) {
        if (this.service == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Warp service is not initialized."));
        }
        return this.service.migrateData(fromStorage, toStorage);
    }

    /** Called by the core asynchronous configuration reload worker. */
    @Override
    public boolean reloadConfig() {
        try {
            WarpConfig next = configManager.tryLoadModuleConfig("warp", WarpConfig.class, WarpConfig.createDefault());
            CompletableFuture<Boolean> applied = new CompletableFuture<>();
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    String oldStorage = config.storage() == null ? "SQLITE" : config.storage().type();
                    String nextStorage = next.storage() == null ? "SQLITE" : next.storage().type();
                    boolean restart = !oldStorage.equalsIgnoreCase(nextStorage)
                        || service == null || !service.isReady()
                        || next.maxCachedWarps() != config.maxCachedWarps()
                        || next.maxStorageBytes() != config.maxStorageBytes()
                        || next.storageQueueCapacity() != config.storageQueueCapacity()
                        || next.maxConcurrentOperations() != config.maxConcurrentOperations()
                        || next.maxPlayerStateEntries() != config.maxPlayerStateEntries()
                        || !next.control().equals(config.control());
                    if (!next.enabled()) {
                        config = next; disable(); stopping.whenComplete((value, failure) -> applied.complete(failure == null));
                    } else if (!isInitialized || restart) {
                        disable(); config = next;
                        long generation = lifecycle.get();
                        stopping.whenComplete((value, failure) -> {
                            if (failure != null) { applied.complete(false); return; }
                            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                                if (generation != lifecycle.get() || !plugin.isEnabled()) { applied.complete(false); return; }
                                try { enable(); service.initialize().whenComplete((done, error) -> applied.complete(error == null)); }
                                catch (RuntimeException error) { applied.completeExceptionally(error); }
                            });
                        });
                    } else {
                        service.updateConfig(next); config = next; applied.complete(true);
                    }
                } catch (RuntimeException failure) { applied.completeExceptionally(failure); }
            });
            return applied.get(next.operationTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception failure) {
            lifecycle.incrementAndGet();
            plugin.getSLF4JLogger().error("Failed to reload Warp module configuration", failure);
            return false;
        }
    }

    public void enable() {
        if (!this.commandsRegistered) {
            this.commandRegistry = new WarpCommandRegistry(this.plugin, this::getService, () -> this.config);
            this.commandRegistry.registerAll();
            this.commandsRegistered = true;
        }
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("Warp Module is disabled in configuration. Skipping registration.");
            return;
        }

        if (this.isInitialized) {
            return;
        }

        instantiateAndInitializeStorage(this.config);

        if (this.eventListener == null) {
            this.eventListener = new WarpEventListener(this::getService, () -> this.config);
            this.plugin.getServer().getPluginManager().registerEvents(this.eventListener, this.plugin);
        }


        this.configManager.registerModule(this);
        this.isInitialized = true;

        this.plugin.getSLF4JLogger().info("Warp Module successfully enabled.");
    }

    private void instantiateAndInitializeStorage(WarpConfig targetConfig) {
        if (this.economyService == null) {
            this.economyService = new VaultWarpEconomyService(this.plugin, () -> this.config, this.plugin.getSLF4JLogger());
        }

        if (targetConfig.storage() != null && "YAML".equalsIgnoreCase(targetConfig.storage().type())) {
            this.repository = new YamlWarpRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger(), targetConfig.storageQueueCapacity(), targetConfig.maxCachedWarps(), targetConfig.maxStorageBytes(), targetConfig.control());
        } else {
            this.repository = new SqliteWarpRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger(), targetConfig.storageQueueCapacity(), targetConfig.maxCachedWarps(), targetConfig.control());
        }

        this.cache = new DefaultWarpCache();
        this.service = new DefaultWarpService(this.plugin, this.repository, this.cache, this.economyService, targetConfig, this.plugin.getSLF4JLogger());
        this.service.initialize().exceptionally(ex -> {
            this.plugin.getSLF4JLogger().error("Failed to asynchronously initialize Warp storage repository", ex);
            return null;
        });
    }

    public void disable() {
        if (!this.isInitialized) {
            return;
        }

        lifecycle.incrementAndGet();

        if (this.eventListener != null) {
            HandlerList.unregisterAll(this.eventListener);
            this.eventListener = null;
        }

        if (this.service != null) {
            stopping = this.service.close();
            this.service = null;
        } else if (this.repository != null) {
            stopping = this.repository.close();
        }
        this.repository = null;

        if (this.cache != null) {
            this.cache.clear();
            this.cache = null;
        }

        if (this.economyService != null) {
            this.economyService = null;
        }

        this.isInitialized = false;
        stopping.exceptionally(failure -> { plugin.getSLF4JLogger().error("Warp asynchronous shutdown failed", failure); return null; });
        this.plugin.getSLF4JLogger().info("Warp Module disabled.");
    }

    public WarpService getService() {
        return this.service;
    }

    public WarpCache getCache() {
        return this.cache;
    }

    public WarpEconomyService getEconomyService() {
        return this.economyService;
    }
}
