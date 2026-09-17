package com.lunatech.tpcore.module.back.repository.impl;

import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.BackRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

public final class SqliteBackRepository implements BackRepository {

    private final File dataFolder;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqliteBackRepository(File dataFolder, Logger logger) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warn("Failed to create database directory: {}", dataFolder.getAbsolutePath());
            }

            File dbFile = new File(dataFolder, "tpcore.db");
            HikariConfig config = new HikariConfig();
            config.setPoolName("TPCore-BackSQLitePool");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setMaximumPoolSize(4);
            config.setConnectionTimeout(5000);
            config.setConnectionTestQuery("SELECT 1");
            config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");

            try {
                this.dataSource = new HikariDataSource(config);
                this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS tpcore_back_locations (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_uuid VARCHAR(36) NOT NULL,
                            world_id VARCHAR(36),
                            world_name VARCHAR(64) NOT NULL,
                            x DOUBLE NOT NULL,
                            y DOUBLE NOT NULL,
                            z DOUBLE NOT NULL,
                            yaw FLOAT NOT NULL,
                            pitch FLOAT NOT NULL,
                            created_at BIGINT NOT NULL,
                            cause VARCHAR(32) NOT NULL DEFAULT 'TELEPORT'
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_tpcore_back_player_id ON tpcore_back_locations(player_uuid, id ASC);");
                    logger.info("SQLite BackRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }

            } catch (Exception e) {
                logger.error("Failed to initialize SQLite database table tpcore_back_locations at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("Back database initialization failure", e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<UUID, List<BackLocation>>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, List<BackLocation>> map = new HashMap<>();
            String sql = "SELECT player_uuid, world_id, world_name, x, y, z, yaw, pitch, created_at, cause FROM tpcore_back_locations ORDER BY id ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuidRaw = rs.getString("player_uuid");
                    if (uuidRaw == null || uuidRaw.isBlank()) continue;
                    UUID uuid = UUID.fromString(uuidRaw);
                    BackLocation loc = mapResultSetToBackLocation(rs);
                    map.computeIfAbsent(uuid, k -> new ArrayList<>()).add(loc);
                }
            } catch (SQLException e) {
                logger.error("Failed to load back locations from SQLite database", e);
            }
            return map;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<List<BackLocation>> loadPlayerHistory(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            List<BackLocation> list = new ArrayList<>();
            String sql = "SELECT player_uuid, world_id, world_name, x, y, z, yaw, pitch, created_at, cause FROM tpcore_back_locations WHERE player_uuid = ? ORDER BY id ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToBackLocation(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load back history for player {} from SQLite database", playerUuid, e);
            }
            return list;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> savePlayerHistory(UUID playerUuid, List<BackLocation> history) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.runAsync(() -> {
            String deleteSql = "DELETE FROM tpcore_back_locations WHERE player_uuid = ?";
            String insertSql = """
                INSERT INTO tpcore_back_locations (player_uuid, world_id, world_name, x, y, z, yaw, pitch, created_at, cause)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement delPs = conn.prepareStatement(deleteSql)) {
                        delPs.setString(1, playerUuid.toString());
                        delPs.executeUpdate();
                    }

                    if (history != null && !history.isEmpty()) {
                        try (PreparedStatement insPs = conn.prepareStatement(insertSql)) {
                            for (BackLocation loc : history) {
                                insPs.setString(1, playerUuid.toString());
                                insPs.setString(2, loc.worldId() != null ? loc.worldId().toString() : null);
                                insPs.setString(3, loc.worldName());
                                insPs.setDouble(4, loc.x());
                                insPs.setDouble(5, loc.y());
                                insPs.setDouble(6, loc.z());
                                insPs.setFloat(7, loc.yaw());
                                insPs.setFloat(8, loc.pitch());
                                insPs.setLong(9, loc.timestamp());
                                insPs.setString(10, loc.cause() != null ? loc.cause().name() : "TELEPORT");
                                insPs.addBatch();
                            }
                            insPs.executeBatch();
                        }
                    }
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                logger.error("Failed to save back history for player {} to SQLite database", playerUuid, e);
                throw new RuntimeException("Database save failure for player back history: " + playerUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> deletePlayerHistory(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM tpcore_back_locations WHERE player_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete back history for player {} from SQLite database", playerUuid, e);
                throw new RuntimeException("Database delete failure for player back history: " + playerUuid, e);
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
                    logger.error("Error shutting down virtualExecutor in SqliteBackRepository", e);
                }
            }
            if (dataSource != null && !dataSource.isClosed()) {
                try {
                    dataSource.close();
                } catch (Exception e) {
                    logger.error("Error closing HikariDataSource in SqliteBackRepository", e);
                }
            }
        });
    }

    private BackLocation mapResultSetToBackLocation(ResultSet rs) throws SQLException {
        String worldIdRaw = rs.getString("world_id");
        UUID worldId = (worldIdRaw != null && !worldIdRaw.isBlank()) ? UUID.fromString(worldIdRaw) : null;
        String worldName = rs.getString("world_name");
        double x = rs.getDouble("x");
        double y = rs.getDouble("y");
        double z = rs.getDouble("z");
        float yaw = rs.getFloat("yaw");
        float pitch = rs.getFloat("pitch");
        long createdAt = rs.getLong("created_at");
        String causeRaw = rs.getString("cause");
        BackCause cause;
        try {
            cause = (causeRaw != null) ? BackCause.valueOf(causeRaw.toUpperCase()) : BackCause.TELEPORT;
        } catch (IllegalArgumentException e) {
            cause = BackCause.TELEPORT;
        }

        return new BackLocation(
            worldId,
            worldName,
            x,
            y,
            z,
            yaw,
            pitch,
            createdAt,
            cause
        );
    }
}
