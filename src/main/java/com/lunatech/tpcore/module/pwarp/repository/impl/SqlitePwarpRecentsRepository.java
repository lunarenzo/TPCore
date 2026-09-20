package com.lunatech.tpcore.module.pwarp.repository.impl;

import com.lunatech.tpcore.module.pwarp.repository.PwarpRecentsRepository;
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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class SqlitePwarpRecentsRepository implements PwarpRecentsRepository {

    private final File dataLocation;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqlitePwarpRecentsRepository(File dataLocation, Logger logger) {
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
            config.setPoolName("TPCore-PwarpRecentsPool");
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
                        CREATE TABLE IF NOT EXISTS tpcore_pwarp_recents (
                            player_uuid VARCHAR(36) NOT NULL,
                            warp_id INTEGER NOT NULL,
                            visited_at BIGINT NOT NULL,
                            PRIMARY KEY (player_uuid, warp_id)
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_recents_player ON tpcore_pwarp_recents(player_uuid, visited_at DESC);");
                    logger.info("SQLite SqlitePwarpRecentsRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SQLite table tpcore_pwarp_recents at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("Pwarp recents database initialization failure", e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> recordVisit(UUID playerUuid, int warpId) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            String sqlUpsert = """
                INSERT INTO tpcore_pwarp_recents (player_uuid, warp_id, visited_at)
                VALUES (?, ?, ?)
                ON CONFLICT(player_uuid, warp_id) DO UPDATE SET visited_at = excluded.visited_at;
            """;
            String sqlPrune = """
                DELETE FROM tpcore_pwarp_recents
                WHERE player_uuid = ? AND warp_id NOT IN (
                    SELECT warp_id FROM tpcore_pwarp_recents
                    WHERE player_uuid = ?
                    ORDER BY visited_at DESC
                    LIMIT 10
                );
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement psUpsert = conn.prepareStatement(sqlUpsert);
                 PreparedStatement psPrune = conn.prepareStatement(sqlPrune)) {
                psUpsert.setString(1, playerUuid.toString());
                psUpsert.setInt(2, warpId);
                psUpsert.setLong(3, System.currentTimeMillis());
                psUpsert.executeUpdate();

                psPrune.setString(1, playerUuid.toString());
                psPrune.setString(2, playerUuid.toString());
                psPrune.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to record recent visit for player {} warp {}", playerUuid, warpId, e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<List<Integer>> getRecents(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            List<Integer> list = new ArrayList<>();
            String sql = "SELECT warp_id FROM tpcore_pwarp_recents WHERE player_uuid = ? ORDER BY visited_at DESC LIMIT 10";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getInt("warp_id"));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load recents for player {}", playerUuid, e);
            }
            return list;
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Map<UUID, List<Integer>>> loadAllRecents() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, List<Integer>> map = new HashMap<>();
            String sql = "SELECT player_uuid, warp_id FROM tpcore_pwarp_recents ORDER BY visited_at DESC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                    int warpId = rs.getInt("warp_id");
                    List<Integer> list = map.computeIfAbsent(uuid, k -> new ArrayList<>());
                    if (list.size() < 10) {
                        list.add(warpId);
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load all recents", e);
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
