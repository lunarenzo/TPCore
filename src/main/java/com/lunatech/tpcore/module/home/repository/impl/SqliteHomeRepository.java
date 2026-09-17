package com.lunatech.tpcore.module.home.repository.impl;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;

public final class SqliteHomeRepository implements HomeRepository {

    private final File dataFolder;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqliteHomeRepository(File dataFolder, Logger logger) {
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
            config.setPoolName("TPCore-SQLitePool");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setMaximumPoolSize(1);
            config.setConnectionTestQuery("SELECT 1");
            config.addDataSourceProperty("journal_mode", "WAL");

            this.dataSource = new HikariDataSource(config);
            this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS tpcore_homes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_uuid VARCHAR(36) NOT NULL,
                        home_name VARCHAR(32) NOT NULL,
                        world_name VARCHAR(64) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        pitch FLOAT NOT NULL,
                        created_at BIGINT NOT NULL,
                        shared_with TEXT DEFAULT '',
                        UNIQUE(owner_uuid, home_name)
                    );
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tpcore_homes_owner ON tpcore_homes(owner_uuid);");
                logger.info("SQLite HomeRepository initialized successfully at {}", dbFile.getAbsolutePath());
            } catch (SQLException e) {
                logger.error("Failed to initialize SQLite database tables", e);
                throw new RuntimeException("Database initialization failure", e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Home>> loadAll(UUID ownerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Home> homes = new HashMap<>();
            String sql = "SELECT home_name, world_name, x, y, z, yaw, pitch, created_at, shared_with FROM tpcore_homes WHERE owner_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ownerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String homeName = rs.getString("home_name");
                        String worldName = rs.getString("world_name");
                        double x = rs.getDouble("x");
                        double y = rs.getDouble("y");
                        double z = rs.getDouble("z");
                        float yaw = rs.getFloat("yaw");
                        float pitch = rs.getFloat("pitch");
                        long createdAt = rs.getLong("created_at");
                        String sharedRaw = rs.getString("shared_with");

                        Set<UUID> sharedWith = parseSharedWith(sharedRaw);
                        Home home = new Home(ownerUuid, homeName, worldName, x, y, z, yaw, pitch, createdAt, sharedWith);
                        homes.put(homeName.toLowerCase(), home);
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to load homes for player {}", ownerUuid, e);
            }
            return homes;
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Optional<Home>> findByName(UUID ownerUuid, String homeName) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT world_name, x, y, z, yaw, pitch, created_at, shared_with FROM tpcore_homes WHERE owner_uuid = ? AND LOWER(home_name) = LOWER(?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ownerUuid.toString());
                ps.setString(2, homeName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String worldName = rs.getString("world_name");
                        double x = rs.getDouble("x");
                        double y = rs.getDouble("y");
                        double z = rs.getDouble("z");
                        float yaw = rs.getFloat("yaw");
                        float pitch = rs.getFloat("pitch");
                        long createdAt = rs.getLong("created_at");
                        String sharedRaw = rs.getString("shared_with");

                        Set<UUID> sharedWith = parseSharedWith(sharedRaw);
                        return Optional.of(new Home(ownerUuid, homeName, worldName, x, y, z, yaw, pitch, createdAt, sharedWith));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to find home {} for player {}", homeName, ownerUuid, e);
            }
            return Optional.empty();
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> save(Home home) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO tpcore_homes (owner_uuid, home_name, world_name, x, y, z, yaw, pitch, created_at, shared_with)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_uuid, home_name) DO UPDATE SET
                    world_name = excluded.world_name,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    yaw = excluded.yaw,
                    pitch = excluded.pitch,
                    shared_with = excluded.shared_with;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, home.ownerUuid().toString());
                ps.setString(2, home.name());
                ps.setString(3, home.worldName());
                ps.setDouble(4, home.x());
                ps.setDouble(5, home.y());
                ps.setDouble(6, home.z());
                ps.setFloat(7, home.yaw());
                ps.setFloat(8, home.pitch());
                ps.setLong(9, home.createdAt());
                ps.setString(10, serializeSharedWith(home.sharedWith()));
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save home {} for player {}", home.name(), home.ownerUuid(), e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> delete(UUID ownerUuid, String homeName) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM tpcore_homes WHERE owner_uuid = ? AND LOWER(home_name) = LOWER(?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ownerUuid.toString());
                ps.setString(2, homeName);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete home {} for player {}", homeName, ownerUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> deleteAll(UUID ownerUuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM tpcore_homes WHERE owner_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ownerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete all homes for player {}", ownerUuid, e);
            }
        }, virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
            if (virtualExecutor != null && !virtualExecutor.isShutdown()) {
                virtualExecutor.shutdown();
            }
        });
    }

    private Set<UUID> parseSharedWith(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> {
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private String serializeSharedWith(Set<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return "";
        }
        return uuids.stream()
            .map(UUID::toString)
            .collect(Collectors.joining(","));
    }
}
