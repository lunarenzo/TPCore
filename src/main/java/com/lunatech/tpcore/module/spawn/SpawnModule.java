package com.lunatech.tpcore.module.spawn;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.command.SpawnCommandRegistry;
import com.lunatech.tpcore.module.spawn.listener.SpawnJoinListener;
import com.lunatech.tpcore.module.spawn.listener.SpawnRespawnListener;
import com.lunatech.tpcore.module.spawn.listener.SpawnVoidListener;
import com.lunatech.tpcore.module.spawn.repository.SpawnRepository;
import com.lunatech.tpcore.module.spawn.repository.impl.YamlSpawnRepository;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import com.lunatech.tpcore.module.spawn.service.impl.DefaultSpawnService;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpawnModule {

    private final JavaPlugin plugin;
    private final SpawnConfig config;

    private SpawnRepository repository;
    private SpawnService service;
    private SpawnJoinListener joinListener;
    private SpawnRespawnListener respawnListener;
    private SpawnVoidListener voidListener;
    private SpawnCommandRegistry commandRegistry;

    public SpawnModule(JavaPlugin plugin, SpawnConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void enable() {
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("Spawn Module is disabled in configuration. Skipping registration.");
            return;
        }

        this.repository = new YamlSpawnRepository(this.plugin, this.plugin.getDataFolder().toPath(), this.plugin.getSLF4JLogger());
        this.service = new DefaultSpawnService(this.plugin, this.repository, this.config);

        this.joinListener = new SpawnJoinListener(this.service, this.config);
        this.respawnListener = new SpawnRespawnListener(this.service, this.config);
        this.voidListener = new SpawnVoidListener(this.service, this.config);

        this.commandRegistry = new SpawnCommandRegistry(this.plugin, this.service, this.config);

        this.plugin.getServer().getPluginManager().registerEvents(this.joinListener, this.plugin);
        this.plugin.getServer().getPluginManager().registerEvents(this.respawnListener, this.plugin);
        this.plugin.getServer().getPluginManager().registerEvents(this.voidListener, this.plugin);

        this.commandRegistry.registerAll();

        this.plugin.getSLF4JLogger().info("Spawn Module successfully enabled.");
    }

    public void disable() {
        if (this.joinListener != null) {
            HandlerList.unregisterAll(this.joinListener);
        }
        if (this.respawnListener != null) {
            HandlerList.unregisterAll(this.respawnListener);
        }
        if (this.voidListener != null) {
            HandlerList.unregisterAll(this.voidListener);
        }
        if (this.service != null) {
            this.service.shutdown();
        }
        this.plugin.getSLF4JLogger().info("Spawn Module disabled.");
    }

    public SpawnService getService() {
        return this.service;
    }
}
