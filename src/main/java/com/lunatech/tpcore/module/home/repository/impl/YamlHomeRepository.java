package com.lunatech.tpcore.module.home.repository.impl;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public final class YamlHomeRepository implements HomeRepository {

    private final File homesDir;
    private final Logger logger;
    private final ExecutorService virtualExecutor;

    public YamlHomeRepository(File dataFolder, Logger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
        this.homesDir = new File(dataFolder, "homes");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (!homesDir.exists() && !homesDir.mkdirs()) {
                logger.warn("Failed to create homes YAML directory: {}", homesDir.getAbsolutePath());
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Map<String, Home>> loadAll(UUID ownerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            File userFile = new File(homesDir, ownerUuid.toString() + ".yml");
            if (!userFile.exists()) {
                return Collections.emptyMap();
            }

            YamlConfigurationLoader loader = createLoader(userFile);
            Map<String, Home> homes = new HashMap<>();

            try {
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode homesNode = root.node("homes");
                if (homesNode.isMap()) {
                    for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : homesNode.childrenMap().entrySet()) {
                        String name = entry.getKey().toString();
                        CommentedConfigurationNode node = entry.getValue();

                        String worldName = node.node("world").getString("world");
                        double x = node.node("x").getDouble(0.0);
                        double y = node.node("y").getDouble(0.0);
                        double z = node.node("z").getDouble(0.0);
                        float yaw = node.node("yaw").getFloat(0.0f);
                        float pitch = node.node("pitch").getFloat(0.0f);
                        long createdAt = node.node("created-at").getLong(System.currentTimeMillis());
                        List<String> sharedRaw = node.node("shared-with").getList(String.class, Collections.emptyList());

                        Set<UUID> sharedWith = sharedRaw.stream()
                            .map(s -> {
                                try {
                                    return UUID.fromString(s);
                                } catch (IllegalArgumentException e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                        Home home = new Home(ownerUuid, name, worldName, x, y, z, yaw, pitch, createdAt, sharedWith);
                        homes.put(name.toLowerCase(), home);
                    }
                }
            } catch (ConfigurateException e) {
                logger.error("Failed to load homes YAML file for player {}", ownerUuid, e);
            }
            return homes;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Optional<Home>> findByName(UUID ownerUuid, String homeName) {
        return loadAll(ownerUuid).thenApply(map -> Optional.ofNullable(map.get(homeName.toLowerCase())));
    }

    @Override
    public CompletableFuture<Void> save(Home home) {
        return CompletableFuture.runAsync(() -> {
            File userFile = new File(homesDir, home.ownerUuid().toString() + ".yml");
            YamlConfigurationLoader loader = createLoader(userFile);

            try {
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode homeNode = root.node("homes", home.name());

                homeNode.node("world").set(home.worldName());
                homeNode.node("x").set(home.x());
                homeNode.node("y").set(home.y());
                homeNode.node("z").set(home.z());
                homeNode.node("yaw").set(home.yaw());
                homeNode.node("pitch").set(home.pitch());
                homeNode.node("created-at").set(home.createdAt());
                homeNode.node("shared-with").set(home.sharedWith().stream().map(UUID::toString).collect(Collectors.toList()));

                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to save home {} to YAML for player {}", home.name(), home.ownerUuid(), e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> delete(UUID ownerUuid, String homeName) {
        return CompletableFuture.runAsync(() -> {
            File userFile = new File(homesDir, ownerUuid.toString() + ".yml");
            if (!userFile.exists()) {
                return;
            }

            YamlConfigurationLoader loader = createLoader(userFile);
            try {
                CommentedConfigurationNode root = loader.load();
                root.node("homes", homeName).set(null);
                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to delete home {} from YAML for player {}", homeName, ownerUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> deleteAll(UUID ownerUuid) {
        return CompletableFuture.runAsync(() -> {
            File userFile = new File(homesDir, ownerUuid.toString() + ".yml");
            if (userFile.exists() && !userFile.delete()) {
                logger.warn("Failed to delete home file {}", userFile.getAbsolutePath());
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            if (virtualExecutor != null && !virtualExecutor.isShutdown()) {
                virtualExecutor.shutdown();
            }
        });
    }

    private YamlConfigurationLoader createLoader(File file) {
        return YamlConfigurationLoader.builder()
            .file(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();
    }
}
