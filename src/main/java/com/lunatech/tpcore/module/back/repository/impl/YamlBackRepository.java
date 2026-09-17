package com.lunatech.tpcore.module.back.repository.impl;

import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.BackRepository;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public final class YamlBackRepository implements BackRepository {

    private final File playersFolder;
    private final Logger logger;
    private final ExecutorService virtualExecutor;

    public YamlBackRepository(File dataFolder, Logger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
        File backDir = new File(dataFolder, "back");
        this.playersFolder = new File(backDir, "players");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (!playersFolder.exists() && !playersFolder.mkdirs()) {
                logger.warn("Failed to create back players YAML directory: {}", playersFolder.getAbsolutePath());
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Map<UUID, List<BackLocation>>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            if (!playersFolder.exists()) {
                return Collections.emptyMap();
            }

            File[] files = playersFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null || files.length == 0) {
                return Collections.emptyMap();
            }

            Map<UUID, List<BackLocation>> map = new HashMap<>();
            for (File file : files) {
                String filename = file.getName();
                String uuidRaw = filename.substring(0, filename.length() - 4);
                UUID playerUuid;
                try {
                    playerUuid = UUID.fromString(uuidRaw);
                } catch (IllegalArgumentException e) {
                    continue;
                }

                List<BackLocation> history = loadPlayerHistoryFromFile(file);
                if (!history.isEmpty()) {
                    map.put(playerUuid, history);
                }
            }
            return map;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<List<BackLocation>> loadPlayerHistory(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = new File(playersFolder, playerUuid + ".yml");
            if (!playerFile.exists()) {
                return Collections.emptyList();
            }
            return loadPlayerHistoryFromFile(playerFile);
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> savePlayerHistory(UUID playerUuid, List<BackLocation> history) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.runAsync(() -> {
            File playerFile = new File(playersFolder, playerUuid + ".yml");
            if (history == null || history.isEmpty()) {
                if (playerFile.exists() && !playerFile.delete()) {
                    logger.warn("Failed to delete empty back history file for player {}", playerUuid);
                }
                return;
            }

            YamlConfigurationLoader loader = createLoader(playerFile);
            try {
                CommentedConfigurationNode root = loader.createNode();
                CommentedConfigurationNode historyNode = root.node("history");
                for (int i = 0; i < history.size(); i++) {
                    BackLocation loc = history.get(i);
                    CommentedConfigurationNode itemNode = historyNode.node(i);
                    itemNode.node("world-id").set(loc.worldId() != null ? loc.worldId().toString() : null);
                    itemNode.node("world").set(loc.worldName());
                    itemNode.node("x").set(loc.x());
                    itemNode.node("y").set(loc.y());
                    itemNode.node("z").set(loc.z());
                    itemNode.node("yaw").set(loc.yaw());
                    itemNode.node("pitch").set(loc.pitch());
                    itemNode.node("timestamp").set(loc.timestamp());
                    itemNode.node("cause").set(loc.cause() != null ? loc.cause().name() : "TELEPORT");
                }
                loader.save(root);
            } catch (ConfigurateException e) {
                logger.error("Failed to save back history for player {} to YAML file", playerUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> deletePlayerHistory(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.runAsync(() -> {
            File playerFile = new File(playersFolder, playerUuid + ".yml");
            if (playerFile.exists() && !playerFile.delete()) {
                logger.warn("Failed to delete back history file for player {}", playerUuid);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            if (virtualExecutor != null && !virtualExecutor.isShutdown()) {
                try {
                    virtualExecutor.shutdown();
                    if (!virtualExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        virtualExecutor.shutdownNow();
                    }
                } catch (Exception e) {
                    logger.error("Error shutting down virtualExecutor in YamlBackRepository", e);
                }
            }
        });
    }

    private List<BackLocation> loadPlayerHistoryFromFile(File file) {
        YamlConfigurationLoader loader = createLoader(file);
        try {
            CommentedConfigurationNode root = loader.load();
            CommentedConfigurationNode historyNode = root.node("history");
            return parseHistoryNode(historyNode);
        } catch (ConfigurateException e) {
            logger.error("Failed to load back history from player YAML file {}", file.getAbsolutePath(), e);
            return Collections.emptyList();
        }
    }



    private List<BackLocation> parseHistoryNode(CommentedConfigurationNode node) {
        List<BackLocation> list = new ArrayList<>();
        if (node.isList()) {
            for (CommentedConfigurationNode itemNode : node.childrenList()) {
                String worldIdRaw = itemNode.node("world-id").getString();
                UUID worldId = (worldIdRaw != null && !worldIdRaw.isBlank()) ? UUID.fromString(worldIdRaw) : null;
                String worldName = itemNode.node("world").getString("world");
                if (worldName == null || worldName.isBlank()) {
                    worldName = "world";
                }
                double x = itemNode.node("x").getDouble(0.0);
                double y = itemNode.node("y").getDouble(0.0);
                double z = itemNode.node("z").getDouble(0.0);
                float yaw = itemNode.node("yaw").getFloat(0.0f);
                float pitch = itemNode.node("pitch").getFloat(0.0f);
                long timestamp = itemNode.node("timestamp").getLong(System.currentTimeMillis());
                String causeRaw = itemNode.node("cause").getString("TELEPORT");
                BackCause cause;
                try {
                    cause = (causeRaw != null) ? BackCause.valueOf(causeRaw.toUpperCase()) : BackCause.TELEPORT;
                } catch (IllegalArgumentException e) {
                    cause = BackCause.TELEPORT;
                }

                list.add(new BackLocation(worldId, worldName, x, y, z, yaw, pitch, timestamp, cause));
            }
        }
        return list;
    }

    private YamlConfigurationLoader createLoader(File file) {
        return YamlConfigurationLoader.builder()
            .file(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();
    }
}
