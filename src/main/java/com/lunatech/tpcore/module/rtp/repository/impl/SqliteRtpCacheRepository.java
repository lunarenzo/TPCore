package com.lunatech.tpcore.module.rtp.repository.impl;

import com.lunatech.tpcore.module.rtp.repository.RtpCacheRepository;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

public final class SqliteRtpCacheRepository implements RtpCacheRepository {

    private final File dataLocation;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqliteRtpCacheRepository(File dataLocation, Logger logger) {
        this.dataLocation = Objects.requireNonNull(dataLocation, "dataLocation cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            File dbFile;
            if (this.dataLocation.isDirectory()) {
                if (!this.dataLocation.exists()) {
                    this.dataLocation.mkdirs();
                }
                dbFile = new File(this.dataLocation, "rtp_cache.db");
            } else {
                File parent = this.dataLocation.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                dbFile = this.dataLocation;
            }

            HikariConfig config = new HikariConfig();
            config.setPoolName("TPCore-RtpSQLitePool");
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
                        CREATE TABLE IF NOT EXISTS tpcore_rtp_cache (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            world_uuid VARCHAR(36) NOT NULL,
                            packed_location BIGINT NOT NULL,
                            created_at BIGINT NOT NULL
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_tpcore_rtp_cache_world ON tpcore_rtp_cache(world_uuid);");
                    logger.info("SQLite RtpCacheRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SQLite table tpcore_rtp_cache at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("RTP database initialization failure", e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Long>> loadPackedLocations(UUID worldUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            List<Long> list = new ArrayList<>();
            String sql = "SELECT packed_location FROM tpcore_rtp_cache WHERE world_uuid = ? ORDER BY id ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getLong("packed_location"));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load packed RTP locations for world {}", worldUuid, e);
            }
            return list;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> savePackedLocation(UUID worldUuid, long packedLocation) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            String sql = "INSERT INTO tpcore_rtp_cache (world_uuid, packed_location, created_at) VALUES (?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                ps.setLong(2, packedLocation);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save packed location {} for world {}", packedLocation, worldUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> deletePackedLocation(UUID worldUuid, long packedLocation) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            String sql = "DELETE FROM tpcore_rtp_cache WHERE world_uuid = ? AND packed_location = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                ps.setLong(2, packedLocation);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete packed location {} for world {}", packedLocation, worldUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> clearAll(UUID worldUuid) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            String sql = "DELETE FROM tpcore_rtp_cache WHERE world_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to clear packed locations for world {}", worldUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            if (this.virtualExecutor != null && !this.virtualExecutor.isShutdown()) {
                this.virtualExecutor.shutdown();
            }
            if (this.dataSource != null && !this.dataSource.isClosed()) {
                this.dataSource.close();
            }
        });
    }
}
