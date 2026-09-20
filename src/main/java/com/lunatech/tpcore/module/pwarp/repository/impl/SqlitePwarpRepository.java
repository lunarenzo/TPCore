package com.lunatech.tpcore.module.pwarp.repository.impl;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRepository;
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

public final class SqlitePwarpRepository implements PwarpRepository {

    private final File dataLocation;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService virtualExecutor;

    public SqlitePwarpRepository(File dataLocation, Logger logger) {
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
            config.setPoolName("TPCore-PwarpSQLitePool");
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
                        CREATE TABLE IF NOT EXISTS tpcore_pwarps (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            owner_uuid VARCHAR(36) NOT NULL,
                            owner_name VARCHAR(16) NOT NULL,
                            name VARCHAR(32) NOT NULL UNIQUE,
                            description TEXT,
                            world_name VARCHAR(64) NOT NULL,
                            x DOUBLE NOT NULL,
                            y DOUBLE NOT NULL,
                            z DOUBLE NOT NULL,
                            yaw FLOAT NOT NULL,
                            pitch FLOAT NOT NULL,
                            icon_material VARCHAR(64) NOT NULL,
                            category VARCHAR(32) NOT NULL DEFAULT 'general',
                            is_private BOOLEAN NOT NULL DEFAULT 0,
                            created_at BIGINT NOT NULL,
                            visits BIGINT NOT NULL DEFAULT 0
                        );
                    """);
                    try {
                        stmt.execute("ALTER TABLE tpcore_pwarps ADD COLUMN category VARCHAR(32) DEFAULT 'general';");
                    } catch (SQLException ignored) {
                        // Migration ignored if column exists
                    }
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pwarps_owner ON tpcore_pwarps(owner_uuid);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pwarps_name ON tpcore_pwarps(name);");
                    logger.info("SQLite SqlitePwarpRepository initialized successfully at {}", dbFile.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SQLite table tpcore_pwarps at {}", dbFile.getAbsolutePath(), e);
                if (this.dataSource != null && !this.dataSource.isClosed()) {
                    this.dataSource.close();
                }
                throw new RuntimeException("Pwarp database initialization failure", e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> savePwarp(Pwarp pwarp) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(pwarp, "pwarp cannot be null");
            String sql = """
                INSERT INTO tpcore_pwarps (owner_uuid, owner_name, name, description, world_name, x, y, z, yaw, pitch, icon_material, category, is_private, created_at, visits)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                    owner_name = excluded.owner_name,
                    description = excluded.description,
                    world_name = excluded.world_name,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    yaw = excluded.yaw,
                    pitch = excluded.pitch,
                    icon_material = excluded.icon_material,
                    category = excluded.category,
                    is_private = excluded.is_private,
                    visits = excluded.visits;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, pwarp.ownerUuid().toString());
                ps.setString(2, pwarp.ownerName());
                ps.setString(3, pwarp.name());
                ps.setString(4, pwarp.description());
                ps.setString(5, pwarp.worldName());
                ps.setDouble(6, pwarp.x());
                ps.setDouble(7, pwarp.y());
                ps.setDouble(8, pwarp.z());
                ps.setFloat(9, pwarp.yaw());
                ps.setFloat(10, pwarp.pitch());
                ps.setString(11, pwarp.iconMaterial());
                ps.setString(12, pwarp.category());
                ps.setBoolean(13, pwarp.isPrivate());
                ps.setLong(14, pwarp.createdAt());
                ps.setLong(15, pwarp.visits());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save pwarp {}", pwarp.name(), e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> deletePwarp(String name) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(name, "name cannot be null");
            String sql = "DELETE FROM tpcore_pwarps WHERE LOWER(name) = LOWER(?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete pwarp {}", name, e);
            }
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Optional<Pwarp>> findByName(String name) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(name, "name cannot be null");
            String sql = "SELECT * FROM tpcore_pwarps WHERE LOWER(name) = LOWER(?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRowToPwarp(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to find pwarp by name {}", name, e);
            }
            return Optional.empty();
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<List<Pwarp>> findByOwner(UUID ownerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Objects.requireNonNull(ownerUuid, "ownerUuid cannot be null");
            List<Pwarp> list = new ArrayList<>();
            String sql = "SELECT * FROM tpcore_pwarps WHERE owner_uuid = ? ORDER BY name ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ownerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRowToPwarp(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to find pwarps for owner {}", ownerUuid, e);
            }
            return list;
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<List<Pwarp>> loadAllPwarps() {
        return CompletableFuture.supplyAsync(() -> {
            List<Pwarp> list = new ArrayList<>();
            String sql = "SELECT * FROM tpcore_pwarps ORDER BY name ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRowToPwarp(rs));
                }
            } catch (SQLException e) {
                logger.error("Failed to load all pwarps", e);
            }
            return list;
        }, this.virtualExecutor);
    }

    @Override
    public CompletableFuture<Void> incrementVisits(String name) {
        return CompletableFuture.runAsync(() -> {
            Objects.requireNonNull(name, "name cannot be null");
            String sql = "UPDATE tpcore_pwarps SET visits = visits + 1 WHERE LOWER(name) = LOWER(?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to increment visits for pwarp {}", name, e);
            }
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

    private Pwarp mapRowToPwarp(ResultSet rs) throws SQLException {
        String category = "general";
        try {
            category = rs.getString("category");
        } catch (SQLException ignored) {}

        return new Pwarp(
            rs.getInt("id"),
            UUID.fromString(rs.getString("owner_uuid")),
            rs.getString("owner_name"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("worldName" != null && rs.getMetaData().getColumnCount() > 0 ? "world_name" : "world_name"),
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("z"),
            rs.getFloat("yaw"),
            rs.getFloat("pitch"),
            rs.getString("icon_material"),
            (category != null && !category.isBlank()) ? category : "general",
            rs.getBoolean("is_private"),
            rs.getLong("created_at"),
            rs.getLong("visits")
        );
    }
}
