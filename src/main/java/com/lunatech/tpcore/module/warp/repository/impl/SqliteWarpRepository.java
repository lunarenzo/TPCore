package com.lunatech.tpcore.module.warp.repository.impl;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

public final class SqliteWarpRepository implements WarpRepository {

    private final File dataFolder;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqliteWarpRepository(File dataFolder, Logger logger) {
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
            config.setPoolName("TPCore-WarpSQLitePool");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setMaximumPoolSize(4);
            config.setConnectionTimeout(5000);
            config.setConnectionTestQuery("SELECT 1");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("busy_timeout", "5000");
            config.addDataSourceProperty("synchronous", "NORMAL");

            try {
                this.dataSource = new HikariDataSource(config);
                this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS tpcore_warps (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            warp_name VARCHAR(32) COLLATE NOCASE NOT NULL UNIQUE,
                            world_id VARCHAR(36),
                            world_name VARCHAR(64) NOT NULL,
                            x DOUBLE NOT NULL,
                            y DOUBLE NOT NULL,
                            z DOUBLE NOT NULL,
                            yaw FLOAT NOT NULL,
                            pitch FLOAT NOT NULL,
                            creator_uuid VARCHAR(36),
                            category VARCHAR(32) COLLATE NOCASE NOT NULL DEFAULT 'general',
                            password_hash TEXT,
                            permission_gated BOOLEAN NOT NULL DEFAULT 0,
                            created_at BIGINT NOT NULL
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_tpcore_warps_category ON tpcore_warps(category);");
                    logger.info("SQLite WarpRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SQLite database table tpcore_warps at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("Warp database initialization failure", e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Warp>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Warp> warps = new HashMap<>();
            String sql = "SELECT warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at FROM tpcore_warps";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Warp warp = mapResultSetToWarp(rs);
                    warps.put(warp.name().toLowerCase(), warp);
                }
            } catch (SQLException e) {
                logger.error("Failed to load warps from SQLite database", e);
            }
            return warps;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Optional<Warp>> findByName(String warpName) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at FROM tpcore_warps WHERE warp_name = ? COLLATE NOCASE";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, warpName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToWarp(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to find warp {} in SQLite database", warpName, e);
            }
            return Optional.empty();
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<List<Warp>> findByCategory(String category) {
        return CompletableFuture.supplyAsync(() -> {
            List<Warp> list = new ArrayList<>();
            String sql = "SELECT warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at FROM tpcore_warps WHERE category = ? COLLATE NOCASE";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, category);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToWarp(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to find warps in category {} in SQLite database", category, e);
            }
            return list;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> save(Warp warp) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO tpcore_warps (warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(warp_name) DO UPDATE SET
                    world_id = excluded.world_id,
                    world_name = excluded.world_name,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    yaw = excluded.yaw,
                    pitch = excluded.pitch,
                    creator_uuid = excluded.creator_uuid,
                    category = excluded.category,
                    password_hash = excluded.password_hash,
                    permission_gated = excluded.permission_gated;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, warp.name());
                ps.setString(2, warp.worldId() != null ? warp.worldId().toString() : null);
                ps.setString(3, warp.worldName());
                ps.setDouble(4, warp.x());
                ps.setDouble(5, warp.y());
                ps.setDouble(6, warp.z());
                ps.setFloat(7, warp.yaw());
                ps.setFloat(8, warp.pitch());
                ps.setString(9, warp.creatorUuid() != null ? warp.creatorUuid().toString() : null);
                ps.setString(10, warp.category());
                ps.setString(11, warp.passwordHash());
                ps.setBoolean(12, warp.permissionGated());
                ps.setLong(13, warp.createdAt());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save warp {} to SQLite database", warp.name(), e);
                throw new RuntimeException("Database save failure for warp: " + warp.name(), e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> delete(String warpName) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM tpcore_warps WHERE warp_name = ? COLLATE NOCASE";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, warpName);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete warp {} from SQLite database", warpName, e);
                throw new RuntimeException("Database delete failure for warp: " + warpName, e);
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
                    logger.error("Error shutting down virtualExecutor in SqliteWarpRepository", e);
                }
            }
            if (dataSource != null && !dataSource.isClosed()) {
                try {
                    dataSource.close();
                } catch (Exception e) {
                    logger.error("Error closing HikariDataSource in SqliteWarpRepository", e);
                }
            }
        });
    }

    private Warp mapResultSetToWarp(ResultSet rs) throws SQLException {
        String name = rs.getString("warp_name");
        String worldIdRaw = rs.getString("world_id");
        UUID worldId = (worldIdRaw != null && !worldIdRaw.isBlank()) ? UUID.fromString(worldIdRaw) : null;
        String worldName = rs.getString("world_name");
        double x = rs.getDouble("x");
        double y = rs.getDouble("y");
        double z = rs.getDouble("z");
        float yaw = rs.getFloat("yaw");
        float pitch = rs.getFloat("pitch");
        String creatorUuidRaw = rs.getString("creator_uuid");
        UUID creatorUuid = (creatorUuidRaw != null && !creatorUuidRaw.isBlank()) ? UUID.fromString(creatorUuidRaw) : null;
        String category = rs.getString("category");
        String passwordHash = rs.getString("password_hash");
        boolean permissionGated = rs.getBoolean("permission_gated");
        long createdAt = rs.getLong("created_at");

        return new Warp(
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
    }
}
