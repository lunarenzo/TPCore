package com.lunatech.tpcore.module.pwarp.repository.impl;

import com.lunatech.tpcore.module.pwarp.model.PwarpRating;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRatingRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class SqlitePwarpRatingRepository implements PwarpRatingRepository {

    private final File dataLocation;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqlitePwarpRatingRepository(File dataLocation, Logger logger) {
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
            config.setPoolName("TPCore-PwarpRatingPool");
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
                        CREATE TABLE IF NOT EXISTS tpcore_pwarp_ratings (
                            warp_id INTEGER NOT NULL,
                            player_uuid VARCHAR(36) NOT NULL,
                            stars INTEGER NOT NULL,
                            created_at BIGINT NOT NULL,
                            PRIMARY KEY (warp_id, player_uuid)
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_ratings_warp ON tpcore_pwarp_ratings(warp_id);");
                    logger.info("SQLite SqlitePwarpRatingRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SQLite table tpcore_pwarp_ratings at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("Pwarp ratings database initialization failure", e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> saveRating(int warpId, UUID playerUuid, int stars) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            String sql = """
                INSERT INTO tpcore_pwarp_ratings (warp_id, player_uuid, stars, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(warp_id, player_uuid) DO UPDATE SET
                    stars = excluded.stars,
                    created_at = excluded.created_at;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                ps.setString(2, playerUuid.toString());
                ps.setInt(3, stars);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save rating for warpId {} and player {}", warpId, playerUuid, e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Optional<PwarpRating>> getRating(int warpId, UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            String sql = "SELECT * FROM tpcore_pwarp_ratings WHERE warp_id = ? AND player_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                ps.setString(2, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRowToRating(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to get rating for warpId {} and player {}", warpId, playerUuid, e);
            }
            return Optional.empty();
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<List<PwarpRating>> getRatingsForWarp(int warpId) {
        return CompletableFuture.supplyAsync(() -> {
            List<PwarpRating> list = new ArrayList<>();
            String sql = "SELECT * FROM tpcore_pwarp_ratings WHERE warp_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRowToRating(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load ratings for warpId {}", warpId, e);
            }
            return list;
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<List<PwarpRating>> loadAllRatings() {
        return CompletableFuture.supplyAsync(() -> {
            List<PwarpRating> list = new ArrayList<>();
            String sql = "SELECT * FROM tpcore_pwarp_ratings";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRowToRating(rs));
                }
            } catch (SQLException e) {
                logger.error("Failed to load all ratings", e);
            }
            return list;
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

    private PwarpRating mapRowToRating(ResultSet rs) throws SQLException {
        return new PwarpRating(
            rs.getInt("warp_id"),
            UUID.fromString(rs.getString("player_uuid")),
            rs.getInt("stars"),
            rs.getLong("created_at")
        );
    }
}
