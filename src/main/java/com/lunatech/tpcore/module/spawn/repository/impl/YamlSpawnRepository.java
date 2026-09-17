package com.lunatech.tpcore.module.spawn.repository.impl;

import com.lunatech.tpcore.module.spawn.model.SpawnLocation;
import com.lunatech.tpcore.module.spawn.repository.SpawnRepository;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class YamlSpawnRepository implements SpawnRepository {

    private final JavaPlugin plugin;
    private final Path dataFile;
    private final Logger logger;
    private final YamlConfigurationLoader loader;
    private final Object fileLock = new Object();

    private final AtomicReference<SpawnLocation> globalSpawn = new AtomicReference<>(null);
    private final Map<String, SpawnLocation> worldSpawns = new ConcurrentHashMap<>();

    public YamlSpawnRepository(JavaPlugin plugin, Path dataDirectory, Logger logger) {
        this.plugin = plugin;
        this.dataFile = dataDirectory.resolve("modules").resolve("spawn_data.yml");
        this.logger = logger;
        this.loader = YamlConfigurationLoader.builder()
            .path(this.dataFile)
            .nodeStyle(NodeStyle.BLOCK)
            .build();
    }

    @Override
    public Optional<SpawnLocation> getGlobalSpawn() {
        return Optional.ofNullable(this.globalSpawn.get());
    }

    @Override
    public void setGlobalSpawn(SpawnLocation spawnLocation) {
        this.globalSpawn.set(spawnLocation);
        this.saveAsync();
    }

    @Override
    public void removeGlobalSpawn() {
        this.globalSpawn.set(null);
        this.saveAsync();
    }

    @Override
    public Optional<SpawnLocation> getWorldSpawn(String worldName) {
        if (worldName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.worldSpawns.get(worldName.toLowerCase()));
    }

    @Override
    public void setWorldSpawn(String worldName, SpawnLocation spawnLocation) {
        if (worldName != null && spawnLocation != null) {
            this.worldSpawns.put(worldName.toLowerCase(), spawnLocation);
            this.saveAsync();
        }
    }

    @Override
    public void removeWorldSpawn(String worldName) {
        if (worldName != null) {
            this.worldSpawns.remove(worldName.toLowerCase());
            this.saveAsync();
        }
    }

    @Override
    public Map<String, SpawnLocation> getAllWorldSpawns() {
        return Collections.unmodifiableMap(new HashMap<>(this.worldSpawns));
    }

    @Override
    public void load() {
        synchronized (this.fileLock) {
            if (!Files.exists(this.dataFile)) {
                return;
            }

            try {
                CommentedConfigurationNode root = this.loader.load();

                CommentedConfigurationNode globalNode = root.node("global-spawn");
                if (!globalNode.virtual()) {
                    SpawnLocation globalLoc = globalNode.get(SpawnLocation.class);
                    this.globalSpawn.set(globalLoc);
                } else {
                    this.globalSpawn.set(null);
                }

                this.worldSpawns.clear();
                CommentedConfigurationNode worldsNode = root.node("world-spawns");
                if (!worldsNode.virtual() && worldsNode.isMap()) {
                    for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : worldsNode.childrenMap().entrySet()) {
                        String world = String.valueOf(entry.getKey());
                        SpawnLocation loc = entry.getValue().get(SpawnLocation.class);
                        if (loc != null) {
                            this.worldSpawns.put(world.toLowerCase(), loc);
                        }
                    }
                }
            } catch (ConfigurateException e) {
                this.logger.error("Failed to load spawn locations from {}", this.dataFile, e);
            }
        }
    }

    private void saveAsync() {
        this.plugin.getServer().getAsyncScheduler().runNow(this.plugin, task -> this.save());
    }

    @Override
    public void save() {
        synchronized (this.fileLock) {
            try {
                CommentedConfigurationNode root = this.loader.createNode();

                SpawnLocation globalLoc = this.globalSpawn.get();
                if (globalLoc != null) {
                    root.node("global-spawn").set(SpawnLocation.class, globalLoc);
                }

                CommentedConfigurationNode worldsNode = root.node("world-spawns");
                for (Map.Entry<String, SpawnLocation> entry : this.worldSpawns.entrySet()) {
                    worldsNode.node(entry.getKey()).set(SpawnLocation.class, entry.getValue());
                }

                this.loader.save(root);
            } catch (ConfigurateException e) {
                this.logger.error("Failed to save spawn locations to {}", this.dataFile, e);
            }
        }
    }
}
