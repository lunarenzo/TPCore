package com.lunatech.tpcore.module.warp.repository.impl;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public final class YamlWarpRepository implements WarpRepository {

    private final File warpFile;
    private final Logger logger;
    private final ExecutorService virtualExecutor;

    public YamlWarpRepository(File dataFolder, Logger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
        File warpsDir = new File(dataFolder, "warps");
        this.warpFile = new File(warpsDir, "warps.yml");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            File parent = warpFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warn("Failed to create warps YAML directory: {}", parent.getAbsolutePath());
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Map<String, Warp>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            if (!warpFile.exists()) {
                return Collections.emptyMap();
            }

            YamlConfigurationLoader loader = createLoader(warpFile);
            Map<String, Warp> warps = new HashMap<>();

            try {
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode warpsNode = root.node("warps");
                if (warpsNode.isMap()) {
                    for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : warpsNode.childrenMap().entrySet()) {
                        String name = entry.getKey().toString();
                        CommentedConfigurationNode node = entry.getValue();

                        String worldIdRaw = node.node("world-id").getString();
                        UUID worldId = (worldIdRaw != null && !worldIdRaw.isBlank()) ? UUID.fromString(worldIdRaw) : null;
                        String worldName = node.node("world").getString("world");
                        if (worldName == null || worldName.isBlank()) {
                            worldName = "world";
                        }
                        double x = node.node("x").getDouble(0.0);
                        double y = node.node("y").getDouble(0.0);
                        double z = node.node("z").getDouble(0.0);
                        float yaw = node.node("yaw").getFloat(0.0f);
                        float pitch = node.node("pitch").getFloat(0.0f);
                        String creatorUuidRaw = node.node("creator-uuid").getString();
                        UUID creatorUuid = (creatorUuidRaw != null && !creatorUuidRaw.isBlank()) ? UUID.fromString(creatorUuidRaw) : null;
                        String category = node.node("category").getString("general");
                        String passwordHash = node.node("password-hash").getString();
                        boolean permissionGated = node.node("permission-gated").getBoolean(false);
                        long createdAt = node.node("created-at").getLong(System.currentTimeMillis());

                        Warp warp = new Warp(
                            name,
                            worldId,
                            worldName,
                            x,
                            y,
                            z,
                            yaw,
                            pitch,
                            creatorUuid,
                            category,
                            passwordHash,
                            permissionGated,
                            createdAt
                        );
                        warps.put(name.toLowerCase(), warp);
                    }
                }
            } catch (ConfigurateException e) {
                logger.error("Failed to load warps from YAML file {}", warpFile.getAbsolutePath(), e);
            }
            return warps;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Optional<Warp>> findByName(String warpName) {
        return loadAll().thenApply(map -> Optional.ofNullable(map.get(warpName.toLowerCase())));
    }

    @Override
    public CompletableFuture<List<Warp>> findByCategory(String category) {
        return loadAll().thenApply(map -> map.values().stream()
            .filter(w -> w.category().equalsIgnoreCase(category))
            .toList());
    }

    @Override
    public CompletableFuture<Void> save(Warp warp) {
        return CompletableFuture.runAsync(() -> {
            YamlConfigurationLoader loader = createLoader(warpFile);
            try {
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode warpNode = root.node("warps", warp.name());

                warpNode.node("world-id").set(warp.worldId() != null ? warp.worldId().toString() : null);
                warpNode.node("world").set(warp.worldName());
                warpNode.node("x").set(warp.x());
                warpNode.node("y").set(warp.y());
                warpNode.node("z").set(warp.z());
                warpNode.node("yaw").set(warp.yaw());
                warpNode.node("pitch").set(warp.pitch());
                warpNode.node("creator-uuid").set(warp.creatorUuid() != null ? warp.creatorUuid().toString() : null);
                warpNode.node("category").set(warp.category());
                warpNode.node("password-hash").set(warp.passwordHash());
                warpNode.node("permission-gated").set(warp.permissionGated());
                warpNode.node("created-at").set(warp.createdAt());

                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to save warp {} to YAML file", warp.name(), e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> delete(String warpName) {
        return CompletableFuture.runAsync(() -> {
            if (!warpFile.exists()) {
                return;
            }
            YamlConfigurationLoader loader = createLoader(warpFile);
            try {
                CommentedConfigurationNode root = loader.load();
                root.node("warps", warpName).set(null);
                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to delete warp {} from YAML file", warpName, e);
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
