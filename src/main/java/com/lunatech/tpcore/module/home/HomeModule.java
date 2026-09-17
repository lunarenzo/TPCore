package com.lunatech.tpcore.module.home;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.cache.HomeCache;
import com.lunatech.tpcore.module.home.cache.impl.DefaultHomeCache;
import com.lunatech.tpcore.module.home.command.HomeCommandRegistry;
import com.lunatech.tpcore.module.home.listener.HomeJoinQuitListener;
import com.lunatech.tpcore.module.home.listener.HomeRespawnListener;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.lunatech.tpcore.module.home.repository.impl.SqliteHomeRepository;
import com.lunatech.tpcore.module.home.service.HomeService;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomeModule implements ReloadableModule {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private volatile HomeConfig config;
    private boolean isInitialized = false;

    private HomeRepository repository;
    private HomeCache cache;
    private HomeService service;
    private HomeJoinQuitListener joinQuitListener;
    private HomeRespawnListener respawnListener;
    private HomeCommandRegistry commandRegistry;

    public HomeModule(JavaPlugin plugin, ModularConfigManager configManager, HomeConfig config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    @Override
    public String getModuleName() {
        return "home";
    }

    @Override
    public boolean reloadConfig() {
        try {
            HomeConfig newConfig = this.configManager.tryLoadModuleConfig("home", HomeConfig.class, HomeConfig.createDefault());
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
            this.plugin.getSLF4JLogger().error("Failed to reload Home module configuration.", e);
        }
        return false;
    }

    public void enable() {
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("Home Module is disabled in configuration. Skipping registration.");
            return;
        }

        if (this.isInitialized) {
            return;
        }

        this.repository = new SqliteHomeRepository(this.plugin.getDataFolder(), this.plugin.getSLF4JLogger());
        this.repository.initialize();

        this.cache = new DefaultHomeCache();
        this.service = new DefaultHomeService(this.plugin, this.repository, this.cache, () -> this.config);

        this.joinQuitListener = new HomeJoinQuitListener(this.repository, this.cache, () -> this.config);
        this.respawnListener = new HomeRespawnListener(this.service, () -> this.config);
        this.commandRegistry = new HomeCommandRegistry(this.plugin, this.service, () -> this.config);

        this.plugin.getServer().getPluginManager().registerEvents(this.joinQuitListener, this.plugin);
        this.plugin.getServer().getPluginManager().registerEvents(this.respawnListener, this.plugin);

        this.commandRegistry.registerAll();
        this.configManager.registerModule(this);
        this.isInitialized = true;

        this.plugin.getSLF4JLogger().info("Home Module successfully enabled with SQLite primary storage.");
    }

    public void disable() {
        if (!this.isInitialized) {
            return;
        }

        this.configManager.unregisterModule("home");

        if (this.joinQuitListener != null) {
            HandlerList.unregisterAll(this.joinQuitListener);
        }
        if (this.respawnListener != null) {
            HandlerList.unregisterAll(this.respawnListener);
        }
        if (this.cache != null) {
            this.cache.clear();
        }
        if (this.repository != null) {
            this.repository.close();
        }

        this.isInitialized = false;
        this.plugin.getSLF4JLogger().info("Home Module disabled.");
    }

    public HomeService getService() {
        return this.service;
    }

    public HomeCache getCache() {
        return this.cache;
    }
}
