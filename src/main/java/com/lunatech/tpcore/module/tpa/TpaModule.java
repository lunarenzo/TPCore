package com.lunatech.tpcore.module.tpa;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.command.TpaCommandRegistry;
import com.lunatech.tpcore.module.tpa.listener.TpaEventListener;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.module.tpa.repository.impl.ConcurrentTpaRepository;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import com.lunatech.tpcore.module.tpa.service.impl.DefaultTpaService;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class TpaModule {

    private final JavaPlugin plugin;
    private final TpaConfig config;

    private TpaRepository repository;
    private TpaService service;
    private TpaEventListener listener;
    private TpaCommandRegistry commandRegistry;

    public TpaModule(JavaPlugin plugin, TpaConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void enable() {
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("TPA Module is disabled in configuration. Skipping registration.");
            return;
        }

        this.repository = new ConcurrentTpaRepository();
        this.service = new DefaultTpaService(this.plugin, this.repository, this.config);
        this.listener = new TpaEventListener(this.service);
        this.commandRegistry = new TpaCommandRegistry(this.plugin, this.service, this.config);

        this.plugin.getServer().getPluginManager().registerEvents(this.listener, this.plugin);
        this.commandRegistry.registerAll();

        this.plugin.getSLF4JLogger().info("TPA Module successfully enabled.");
    }

    public void disable() {
        if (this.listener != null) {
            HandlerList.unregisterAll(this.listener);
        }
        if (this.service != null) {
            this.service.shutdown();
        }
        if (this.repository != null) {
            this.repository.clear();
        }
        this.plugin.getSLF4JLogger().info("TPA Module disabled.");
    }

    public TpaService getService() {
        return this.service;
    }
}
