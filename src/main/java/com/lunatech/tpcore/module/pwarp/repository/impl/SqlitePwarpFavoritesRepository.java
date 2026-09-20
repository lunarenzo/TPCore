package com.lunatech.tpcore.module.pwarp.repository.impl;

import com.lunatech.tpcore.module.pwarp.repository.PwarpFavoritesRepository;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class SqlitePwarpFavoritesRepository implements PwarpFavoritesRepository {

    private final File dataLocation;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqlitePwarpFavoritesRepository(File dataLocation, Logger logger) {
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
            config.setPoolName("TPCore-PwarpFavoritesPool");
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
                        CREATE TABLE IF NOT EXISTS tpcore_pwarp_favorites (
                            player_uuid VARCHAR(36) NOT NULL,
                            warp_id INTEGER NOT NULL,
                            added_at BIGINT NOT NULL,
                            PRIMARY KEY (player_uuid, warp_id)
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_favorites_player ON tpcore_pwarp_favorites(player_uuid);");
                    logger.info("SQLite SqlitePwarpFavoritesRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SQLite table tpcore_pwarp_favorites at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("Pwarp favorites database initialization failure", e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> addFavorite(UUID playerUuid, int warpId) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            String sql = """
                INSERT INTO tpcore_pwarp_favorites (player_uuid, warp_id, added_at)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid, warp_id) DO UPDATE SET added_at = excluded.added_at;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setInt(2, warpId);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to add favorite for player {} warp {}", playerUuid, warpId, e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> removeFavorite(UUID playerUuid, int warpId) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            String sql = "DELETE FROM tpcore_pwarp_favorites WHERE player_uuid = ? AND warp_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setInt(2, warpId);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to remove favorite for player {} warp {}", playerUuid, warpId, e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Set<Integer>> getFavorites(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            Set<Integer> set = new HashSet<>();
            String sql = "SELECT warp_id FROM tpcore_pwarp_favorites WHERE player_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        set.add(rs.getInt("warp_id"));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load favorites for player {}", playerUuid, e);
            }
            return set;
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Map<UUID, Set<Integer>>> loadAllFavorites() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, Set<Integer>> map = new HashMap<>();
            String sql = "SELECT player_uuid, warp_id FROM tpcore_pwarp_favorites";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                    int warpId = rs.getInt("warp_id");
                    map.computeIfAbsent(uuid, k -> new HashSet<>()).add(warpId);
                }
            } catch (SQLException e) {
                logger.error("Failed to load all favorites", e);
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
