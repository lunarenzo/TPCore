package com.lunatech.tpcore.module.pwarp.repository.impl;

import com.lunatech.tpcore.module.pwarp.repository.PwarpAccessRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class SqlitePwarpAccessRepository implements PwarpAccessRepository {

    private final File dataLocation;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqlitePwarpAccessRepository(File dataLocation, Logger logger) {
        this.dataLocation = Objects.requireNonNull(dataLocation, "dataLocation cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            File dbFile;
            if (this.dataLocation.isDirectory()) {
                if (!this.dataLocation.exists()) {
                    this.dataLocation.mkdirs();
                }
                dbFile = new File(this.dataLocation, "pwarps.db");
            } else {
                File parent = this.dataLocation.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                dbFile = this.dataLocation;
            }

            HikariConfig config = new HikariConfig();
            config.setPoolName("TPCore-PwarpAccessPool");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setMaximumPoolSize(1);
            config.setConnectionTimeout(5000);
            config.setConnectionTestQuery("SELECT 1");
            config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");

            try {
                this.dataSource = new HikariDataSource(config);

                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS tpcore_pwarp_access_lists (
                            warp_id INTEGER NOT NULL,
                            player_uuid VARCHAR(36) NOT NULL,
                            list_type VARCHAR(16) NOT NULL,
                            PRIMARY KEY (warp_id, player_uuid, list_type)
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_access_warp ON tpcore_pwarp_access_lists(warp_id, list_type);");
                    logger.info("SQLite SqlitePwarpAccessRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SQLite table tpcore_pwarp_access_lists at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("Pwarp access list database initialization failure", e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> addEntry(int warpId, UUID playerUuid, String listType) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            Objects.requireNonNull(listType, "listType cannot be null");
            String cleanType = listType.trim().toUpperCase(Locale.ROOT);
            String sql = "INSERT OR IGNORE INTO tpcore_pwarp_access_lists (warp_id, player_uuid, list_type) VALUES (?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, cleanType);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to add access entry for warp {} player {} type {}", warpId, playerUuid, cleanType, e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> removeEntry(int warpId, UUID playerUuid, String listType) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            Objects.requireNonNull(listType, "listType cannot be null");
            String cleanType = listType.trim().toUpperCase(Locale.ROOT);
            String sql = "DELETE FROM tpcore_pwarp_access_lists WHERE warp_id = ? AND player_uuid = ? AND list_type = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, cleanType);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to remove access entry for warp {} player {} type {}", warpId, playerUuid, cleanType, e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Set<UUID>> getEntries(int warpId, String listType) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(listType, "listType cannot be null");
            String cleanType = listType.trim().toUpperCase(Locale.ROOT);
            Set<UUID> set = new HashSet<>();
            String sql = "SELECT player_uuid FROM tpcore_pwarp_access_lists WHERE warp_id = ? AND list_type = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                ps.setString(2, cleanType);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        set.add(UUID.fromString(rs.getString("player_uuid")));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load access entries for warp {} type {}", warpId, cleanType, e);
            }
            return set;
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Map<Integer, Set<UUID>>> loadAllEntries(String listType) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(listType, "listType cannot be null");
            String cleanType = listType.trim().toUpperCase(Locale.ROOT);
            Map<Integer, Set<UUID>> map = new HashMap<>();
            String sql = "SELECT warp_id, player_uuid FROM tpcore_pwarp_access_lists WHERE list_type = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, cleanType);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int warpId = rs.getInt("warp_id");
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        map.computeIfAbsent(warpId, k -> new HashSet<>()).add(uuid);
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load all access entries for type {}", cleanType, e);
            }
            return map;
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            if (this.virtualExecutor != null && !this.virtualExecutor.isShutdown()) {
                this.virtualExecutor.shutdown();
                try {
                    if (!this.virtualExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                        this.virtualExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    this.virtualExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            if (this.dataSource != null && !this.dataSource.isClosed()) {
                this.dataSource.close();
            }
        });
    }
}
