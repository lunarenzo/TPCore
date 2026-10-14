package com.lunatech.tpcore.module.warp.repository.impl;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.util.WarpIoExecutor;
import java.util.Locale;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public final class YamlWarpRepository implements WarpRepository {

    private final File warpFile;
    private final Logger logger;
    private final WarpIoExecutor virtualExecutor;
    private final int rowLimit;
    private final long maxStorageBytes;
    private final ReentrantLock fileLock = new ReentrantLock();

    public YamlWarpRepository(File dataFolder, Logger logger) {
        this(dataFolder, logger, WarpConfig.createDefault().storageQueueCapacity());
    }

    public YamlWarpRepository(File dataFolder, Logger logger, int queueCapacity) {
        this(dataFolder, logger, queueCapacity, WarpConfig.createDefault().maxCachedWarps(), WarpConfig.createDefault().maxStorageBytes());
    }

    public YamlWarpRepository(File dataFolder, Logger logger, int queueCapacity, int rowLimit, long maxStorageBytes) {
        Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
        File warpsDir = new File(dataFolder, "warps");
        this.warpFile = new File(warpsDir, "warps.yml");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.virtualExecutor = new WarpIoExecutor(queueCapacity);
        this.rowLimit = Math.max(1, rowLimit);
        this.maxStorageBytes = maxStorageBytes;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return virtualExecutor.run(() -> {
            File parent = warpFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warn("Failed to create warps YAML directory: {}", parent.getAbsolutePath());
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Warp>> loadAll() {
        return virtualExecutor.submit(() -> {
            fileLock.lock();
            try {
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
                            if (warps.size() >= rowLimit) throw new IllegalStateException("Warp storage exceeds configured cache capacity");
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
                            warps.put(name.toLowerCase(Locale.ROOT), warp);
                        }
                    }
                } catch (ConfigurateException e) {
                    logger.error("Failed to load warps from YAML file {}", warpFile.getAbsolutePath(), e);
                    throw new IllegalStateException("Failed to load warp storage", e);
                }
                return warps;
            } finally {
                fileLock.unlock();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Warp>> findByName(String warpName) {
        return loadAll().thenApply(map -> Optional.ofNullable(map.get(warpName.toLowerCase(Locale.ROOT))));
    }

    @Override
    public CompletableFuture<List<Warp>> findByCategory(String category) {
        return loadAll().thenApply(map -> map.values().stream()
            .filter(w -> w.category().equalsIgnoreCase(category))
            .toList());
    }

    @Override
    public CompletableFuture<Void> save(Warp warp) {
        return virtualExecutor.run(() -> {
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile);
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode warpsNode = root.node("warps");

                // Clean up any existing case-variant key before writing new key
                if (warpsNode.isMap()) {
                    for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : warpsNode.childrenMap().entrySet()) {
                        if (entry.getKey().toString().equalsIgnoreCase(warp.name()) && !entry.getKey().toString().equals(warp.name())) {
                            warpsNode.node(entry.getKey()).set(null);
                        }
                    }
                }

                writeWarpToNode(warpsNode.node(warp.name()), warp);
                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to save warp {} to YAML file", warp.name(), e);
                throw new IllegalStateException("Failed to save warp storage", e);
            } finally {
                fileLock.unlock();
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveAll(Collection<Warp> warps) {
        return virtualExecutor.run(() -> {
            if (warps == null || warps.isEmpty()) {
                return;
            }
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile);
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode warpsNode = root.node("warps");

                for (Warp warp : warps) {
                    if (warpsNode.isMap()) {
                        for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : warpsNode.childrenMap().entrySet()) {
                            if (entry.getKey().toString().equalsIgnoreCase(warp.name()) && !entry.getKey().toString().equals(warp.name())) {
                                warpsNode.node(entry.getKey()).set(null);
                            }
                        }
                    }
                    writeWarpToNode(warpsNode.node(warp.name()), warp);
                }

                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to batch save {} warps to YAML file", warps.size(), e);
                throw new IllegalStateException("Failed to batch save warp storage", e);
            } finally {
                fileLock.unlock();
            }
        });
    }

    @Override
    public CompletableFuture<Void> delete(String warpName) {
        return virtualExecutor.run(() -> {
            if (!warpFile.exists()) {
                return;
            }
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile);
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode warpsNode = root.node("warps");
                if (warpsNode.isMap()) {
                    for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : warpsNode.childrenMap().entrySet()) {
                        if (entry.getKey().toString().equalsIgnoreCase(warpName)) {
                            warpsNode.node(entry.getKey()).set(null);
                        }
                    }
                }
                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to delete warp {} from YAML file", warpName, e);
                throw new IllegalStateException("Failed to delete warp storage", e);
            } finally {
                fileLock.unlock();
            }
        });
    }

    @Override
    public CompletableFuture<Void> replace(String oldName, Warp updated) {
        return virtualExecutor.run(() -> {
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile);
                CommentedConfigurationNode root = loader.load();
                CommentedConfigurationNode warps = root.node("warps");
                List<Object> sourceKeys = new ArrayList<>();
                for (Object key : warps.childrenMap().keySet()) {
                    String name = key.toString();
                    if (name.equalsIgnoreCase(oldName)) sourceKeys.add(key);
                    else if (name.equalsIgnoreCase(updated.name())) throw new IllegalStateException("Warp rename collision");
                }
                if (sourceKeys.isEmpty()) throw new IllegalStateException("Warp disappeared during edit");
                for (Object sourceKey : sourceKeys) warps.node(sourceKey).set(null);
                writeWarpToNode(warps.node(updated.name()), updated);
                loader.save(root);
            } catch (ConfigurateException failure) { throw new IllegalStateException("Warp edit failed", failure); }
            finally { fileLock.unlock(); }
        });
    }

    @Override
    public CompletableFuture<Void> close() {
        return virtualExecutor.close(() -> {
        });
    }

    private void writeWarpToNode(CommentedConfigurationNode warpNode, Warp warp) throws ConfigurateException {
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
    }

    private YamlConfigurationLoader createLoader(File file) {
        if (file.length() > maxStorageBytes) throw new IllegalStateException("Warp YAML exceeds configured storage byte limit");
        return YamlConfigurationLoader.builder()
            .file(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();
    }
}
