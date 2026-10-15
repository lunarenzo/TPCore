package com.lunatech.tpcore.module.warp.repository.impl;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.config.model.WarpConfig.WarpControlConfig;
import java.util.concurrent.TimeUnit;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.util.WarpIoExecutor;
import java.util.Locale;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
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
    private final WarpControlConfig control;
    private final ReentrantLock fileLock = new ReentrantLock();

    public YamlWarpRepository(File dataFolder, Logger logger) {
        this(dataFolder, logger, WarpConfig.createDefault().storageQueueCapacity());
    }

    public YamlWarpRepository(File dataFolder, Logger logger, int queueCapacity) {
        this(dataFolder, logger, queueCapacity, WarpConfig.createDefault().maxCachedWarps(), WarpConfig.createDefault().maxStorageBytes());
    }

    public YamlWarpRepository(File dataFolder, Logger logger, int queueCapacity, int rowLimit, long maxStorageBytes) {
        this(dataFolder, logger, queueCapacity, rowLimit, maxStorageBytes, WarpControlConfig.createDefault());
    }

    public YamlWarpRepository(File dataFolder, Logger logger, int queueCapacity, int rowLimit, long maxStorageBytes, WarpControlConfig control) {
        this.control = control;
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

                            Warp warp = readWarp(name, node);
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
                saveBounded(root);
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

                saveBounded(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to batch save {} warps to YAML file", warps.size(), e);
                throw new IllegalStateException("Failed to batch save warp storage", e);
            } finally {
                fileLock.unlock();
            }
        });
    }

    @Override public CompletableFuture<Void> mergeSnapshot(Collection<Warp> warps, Collection<DeletedWarp> history) {
        return virtualExecutor.run(() -> {
            fileLock.lock();
            try {
                CommentedConfigurationNode root = createLoader(warpFile).load();
                CommentedConfigurationNode active = root.node("warps");
                for (Warp warp : warps) {
                    for (Object key : new ArrayList<>(active.childrenMap().keySet())) {
                        if (key.toString().equalsIgnoreCase(warp.name())) active.node(key).set(null);
                    }
                    writeWarpToNode(active.node(warp.name()), warp);
                }
                if (active.childrenMap().size() > rowLimit) throw new IllegalStateException("Warp snapshot exceeds capacity");
                long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(control.recoveryRetentionDays());
                for (DeletedWarp entry : history) {
                    if (entry.deletedAt() > cutoff && !root.node("deleted-warps").childrenMap().containsKey(entry.id().toString())) writeDeleted(root, entry);
                }
                pruneDeleted(root);
                saveBounded(root);
            } catch (ConfigurateException failure) { throw new IllegalStateException("Cannot merge warp snapshot", failure); }
            finally { fileLock.unlock(); }
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
                saveBounded(root);
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
                saveBounded(root);
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
        warpNode.node("closed").set(warp.closed());
        warpNode.node("closure-reason").set(warp.closureReason());
        warpNode.node("unlisted").set(warp.unlisted());
    }

    private void saveBounded(CommentedConfigurationNode root) {
        Path target = warpFile.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".pending");
        try {
            Files.createDirectories(target.getParent());
            YamlConfigurationLoader.builder().file(temporary.toFile()).nodeStyle(NodeStyle.BLOCK).build().save(root);
            if (Files.size(temporary) > maxStorageBytes) throw new IllegalStateException("Proposed warp YAML exceeds configured storage byte limit");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist bounded warp YAML", failure); }
        finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException failure) { logger.warn("Cannot remove pending warp YAML {}", temporary, failure); }
        }
    }

    private YamlConfigurationLoader createLoader(File file) {
        if (file.length() > maxStorageBytes) throw new IllegalStateException("Warp YAML exceeds configured storage byte limit");
        return YamlConfigurationLoader.builder()
            .file(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();
    }

    private Warp readWarp(String name, CommentedConfigurationNode node) {
        String world = node.node("world").getString("world");
        String worldId = node.node("world-id").getString();
        String creator = node.node("creator-uuid").getString();
        return new Warp(name, worldId == null || worldId.isBlank() ? null : UUID.fromString(worldId),
            world == null || world.isBlank() ? "world" : world,
            node.node("x").getDouble(0), node.node("y").getDouble(0), node.node("z").getDouble(0),
            node.node("yaw").getFloat(0), node.node("pitch").getFloat(0),
            creator == null || creator.isBlank() ? null : UUID.fromString(creator), node.node("category").getString("general"),
            node.node("password-hash").getString(), node.node("permission-gated").getBoolean(false), node.node("created-at").getLong(0),
            node.node("closed").getBoolean(false), node.node("closure-reason").getString(""), node.node("unlisted").getBoolean(false));
    }

    private Object activeKey(CommentedConfigurationNode root, String name) {
        Object found = null;
        for (Object key : root.node("warps").childrenMap().keySet()) if (key.toString().equalsIgnoreCase(name)) found = key;
        return found;
    }

    private boolean pruneDeleted(CommentedConfigurationNode root) throws ConfigurateException {
        CommentedConfigurationNode history = root.node("deleted-warps");
        var entries = new ArrayList<>(history.childrenMap().entrySet());
        entries.sort((left, right) -> {
            int comparison = Long.compare(right.getValue().node("deleted-at").getLong(0), left.getValue().node("deleted-at").getLong(0));
            return comparison != 0 ? comparison : right.getKey().toString().compareTo(left.getKey().toString());
        });
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(control.recoveryRetentionDays());
        int kept = 0; boolean changed = false;
        for (var entry : entries) {
            if (entry.getValue().node("deleted-at").getLong(0) <= cutoff || kept >= control.recoveryMaxEntries()) {
                history.node(entry.getKey()).set(null); changed = true;
            } else kept++;
        }
        return changed;
    }

    private DeletedWarp readDeleted(String id, CommentedConfigurationNode node) {
        String actor = node.node("deleted-by").getString();
        CommentedConfigurationNode snapshot = node.node("warp");
        String name = snapshot.node("name").getString();
        return new DeletedWarp(UUID.fromString(id), readWarp(name, snapshot), node.node("deleted-at").getLong(0),
            actor == null ? null : UUID.fromString(actor));
    }

    private void writeDeleted(CommentedConfigurationNode root, DeletedWarp entry) throws ConfigurateException {
        CommentedConfigurationNode node = root.node("deleted-warps", entry.id().toString());
        node.node("deleted-at").set(entry.deletedAt());
        node.node("deleted-by").set(entry.deletedBy() == null ? null : entry.deletedBy().toString());
        node.node("warp", "name").set(entry.warp().name());
        writeWarpToNode(node.node("warp"), entry.warp());
    }

    @Override public CompletableFuture<List<DeletedWarp>> loadDeleted() {
        return virtualExecutor.submit(() -> {
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile); CommentedConfigurationNode root = loader.load();
                if (pruneDeleted(root)) saveBounded(root);
                List<DeletedWarp> entries = new ArrayList<>();
                for (var entry : root.node("deleted-warps").childrenMap().entrySet()) entries.add(readDeleted(entry.getKey().toString(), entry.getValue()));
                entries.sort((left, right) -> {
                    int comparison = Long.compare(right.deletedAt(), left.deletedAt());
                    return comparison != 0 ? comparison : right.id().toString().compareTo(left.id().toString());
                });
                return List.copyOf(entries);
            } catch (ConfigurateException failure) { throw new IllegalStateException("Cannot read deleted warps", failure); }
            finally { fileLock.unlock(); }
        });
    }

    @Override public CompletableFuture<Void> archiveAndDelete(DeletedWarp entry) {
        return virtualExecutor.run(() -> {
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile); CommentedConfigurationNode root = loader.load();
                Object key = activeKey(root, entry.warp().name());
                if (key == null || !entry.warp().equals(readWarp(key.toString(), root.node("warps", key)))) throw new IllegalStateException("Warp changed before deletion");
                if (root.node("deleted-warps").childrenMap().containsKey(entry.id().toString())) throw new IllegalStateException("Recovery ID collision");
                if (entry.deletedAt() <= System.currentTimeMillis() - TimeUnit.DAYS.toMillis(control.recoveryRetentionDays())) throw new IllegalStateException("Deletion snapshot expired before persistence");
                writeDeleted(root, entry);
                for (Object existing : new ArrayList<>(root.node("warps").childrenMap().keySet())) {
                    if (existing.toString().equalsIgnoreCase(entry.warp().name())) root.node("warps", existing).set(null);
                }
                pruneDeleted(root); saveBounded(root);
            } catch (ConfigurateException failure) { throw new IllegalStateException("Cannot archive and delete warp", failure); }
            finally { fileLock.unlock(); }
        });
    }

    @Override public CompletableFuture<Void> restore(DeletedWarp entry) {
        return virtualExecutor.run(() -> {
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile); CommentedConfigurationNode root = loader.load();
                pruneDeleted(root);
                if (!root.node("deleted-warps").childrenMap().containsKey(entry.id().toString())
                    || !entry.equals(readDeleted(entry.id().toString(), root.node("deleted-warps", entry.id().toString())))) throw new IllegalStateException("Recovery entry missing or changed");
                if (activeKey(root, entry.warp().name()) != null) throw new IllegalStateException("Recovery name collision");
                writeWarpToNode(root.node("warps", entry.warp().name()), entry.warp());
                root.node("deleted-warps", entry.id().toString()).set(null); saveBounded(root);
            } catch (ConfigurateException failure) { throw new IllegalStateException("Cannot restore warp", failure); }
            finally { fileLock.unlock(); }
        });
    }

    @Override public CompletableFuture<Void> saveDeleted(Collection<DeletedWarp> entries) {
        return virtualExecutor.run(() -> {
            fileLock.lock();
            try {
                YamlConfigurationLoader loader = createLoader(warpFile); CommentedConfigurationNode root = loader.load();
                long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(control.recoveryRetentionDays());
                for (DeletedWarp entry : entries) {
                    if (entry.deletedAt() > cutoff && !root.node("deleted-warps").childrenMap().containsKey(entry.id().toString())) writeDeleted(root, entry);
                }
                pruneDeleted(root); saveBounded(root);
            } catch (ConfigurateException failure) { throw new IllegalStateException("Cannot migrate deleted warps", failure); }
            finally { fileLock.unlock(); }
        });
    }
}
