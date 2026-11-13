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
import java.lang.Runtime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class SqliteRtpCacheRepository implements RtpCacheRepository {

    private final File dataLocation;
    private final Logger logger;
    private HikariDataSource dataSource;
    private final ExecutorService virtualExecutor;
    private final int retention, ageMinutes;
    private final Thread shutdownHook;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean closing;

    public SqliteRtpCacheRepository(File dataLocation, Logger logger) {
        this(dataLocation, logger, 64, 64, 60);
    }

    public SqliteRtpCacheRepository(File dataLocation, Logger logger, int capacity, int retention, int ageMinutes) {
        this.retention = retention; this.ageMinutes = ageMinutes;
        this.dataLocation = Objects.requireNonNull(dataLocation, "dataLocation cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.virtualExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
            Thread.ofVirtual().name("tpcore-rtp-sqlite-", 0).factory());
        shutdownHook = Thread.ofPlatform().name("tpcore-rtp-drain").unstarted(() -> {
            try { close().get(11, TimeUnit.SECONDS); }
            catch (Exception failure) { logger.error("RTP cache drain incomplete at JVM exit", failure); }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return write(() -> {
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
            config.setMaximumPoolSize(1);
            config.setConnectionTimeout(5000);
            config.setConnectionTestQuery("SELECT 1");
            config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;");

            try {
                this.dataSource = new HikariDataSource(config);

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
                    try (var prune = conn.prepareStatement("DELETE FROM tpcore_rtp_cache WHERE created_at < ? OR id IN (SELECT id FROM tpcore_rtp_cache ORDER BY id DESC LIMIT -1 OFFSET ?)")) {
                        prune.setLong(1, System.currentTimeMillis() - ageMinutes * 60_000L); prune.setInt(2, retention * 64);
                        prune.executeUpdate();
                    }
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
        return read(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            List<Long> list = new ArrayList<>();
            String sql = "SELECT packed_location FROM tpcore_rtp_cache WHERE world_uuid = ? AND created_at >= ? ORDER BY id DESC LIMIT ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                ps.setLong(2, System.currentTimeMillis() - ageMinutes * 60_000L);
                ps.setInt(3, retention);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getLong("packed_location"));
                    }
                }
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
            return list;
        });
    }

    @Override
    public CompletableFuture<Void> savePackedLocation(UUID worldUuid, long packedLocation) {
        return write(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            String sql = "INSERT INTO tpcore_rtp_cache (world_uuid, packed_location, created_at) VALUES (?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                ps.setLong(2, packedLocation);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
                try (var prune = conn.prepareStatement("DELETE FROM tpcore_rtp_cache WHERE created_at < ? OR id IN (SELECT id FROM tpcore_rtp_cache WHERE world_uuid=? ORDER BY id DESC LIMIT -1 OFFSET ?) OR id IN (SELECT id FROM tpcore_rtp_cache ORDER BY id DESC LIMIT -1 OFFSET ?)")) {
                    prune.setLong(1, System.currentTimeMillis() - ageMinutes * 60_000L);
                    prune.setString(2, worldUuid.toString()); prune.setInt(3, retention); prune.setInt(4, retention * 64);
                    prune.executeUpdate();
                }
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deletePackedLocation(UUID worldUuid, long packedLocation) {
        return write(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            String sql = "DELETE FROM tpcore_rtp_cache WHERE world_uuid = ? AND packed_location = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                ps.setLong(2, packedLocation);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> clearAll(UUID worldUuid) {
        return write(() -> {
            Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
            String sql = "DELETE FROM tpcore_rtp_cache WHERE world_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, worldUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
    }

    private synchronized <T> CompletableFuture<T> read(Supplier<T> action) {
        if (closing) return CompletableFuture.failedFuture(new RejectedExecutionException("RTP storage closed"));
        try { return CompletableFuture.supplyAsync(action, virtualExecutor); }
        catch (RejectedExecutionException busy) { return CompletableFuture.failedFuture(busy); }
    }
    private CompletableFuture<Void> write(Runnable action) { return read(() -> { action.run(); return null; }); }

    @Override public synchronized CompletableFuture<Void> close() {
        if (closing) return stopped;
        closing = true;
        virtualExecutor.shutdown();
        CompletableFuture.runAsync(() -> {
            try {
                if (!virtualExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    var failure = new IllegalStateException("RTP cache storage drain timed out");
                    logger.error("RTP cache drain exceeded its deadline; closing the pool once accepted work finishes", failure);
                    stopped.completeExceptionally(failure);
                    while (!virtualExecutor.awaitTermination(1, TimeUnit.DAYS)) {
                        logger.warn("RTP cache worker is still draining");
                    }
                }
                if (dataSource != null) dataSource.close();
                stopped.complete(null);
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException exiting) { /* JVM already owns the hook. */ }
            } catch (Exception failure) { stopped.completeExceptionally(failure); }
        });
        return stopped;
    }
}
