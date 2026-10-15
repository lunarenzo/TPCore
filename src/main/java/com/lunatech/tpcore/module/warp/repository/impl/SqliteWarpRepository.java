package com.lunatech.tpcore.module.warp.repository.impl;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.config.model.WarpConfig.WarpControlConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.util.WarpIoExecutor;
import java.util.Locale;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

public final class SqliteWarpRepository implements WarpRepository {

    private final File dataFolder;
    private final Logger logger;
    private HikariDataSource dataSource;
    private final WarpIoExecutor virtualExecutor;
    private final int rowLimit;
    private final WarpControlConfig control;
    private static final String WARP_COLUMNS = "warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at, closed, closure_reason, unlisted";

    public SqliteWarpRepository(File dataFolder, Logger logger) {
        this(dataFolder, logger, WarpConfig.createDefault().storageQueueCapacity());
    }

    public SqliteWarpRepository(File dataFolder, Logger logger, int queueCapacity) {
        this(dataFolder, logger, queueCapacity, WarpConfig.createDefault().maxCachedWarps());
    }

    public SqliteWarpRepository(File dataFolder, Logger logger, int queueCapacity, int rowLimit) {
        this(dataFolder, logger, queueCapacity, rowLimit, WarpControlConfig.createDefault());
    }

    public SqliteWarpRepository(File dataFolder, Logger logger, int queueCapacity, int rowLimit, WarpControlConfig control) {
        this.control = control;
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.virtualExecutor = new WarpIoExecutor(queueCapacity);
        this.rowLimit = Math.max(1, rowLimit);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return virtualExecutor.run(() -> {
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.warn("Failed to create database directory: {}", dataFolder.getAbsolutePath());
            }

            File dbFile = new File(dataFolder, "tpcore.db");
            HikariConfig config = new HikariConfig();
            config.setPoolName("TPCore-WarpSQLitePool");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setMaximumPoolSize(1);
            config.setConnectionTimeout(5000);
            config.setConnectionTestQuery("SELECT 1");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("busy_timeout", "5000");
            config.addDataSourceProperty("synchronous", "NORMAL");

            try {
                this.dataSource = new HikariDataSource(config);

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
                            created_at BIGINT NOT NULL,
                            closed BOOLEAN NOT NULL DEFAULT 0,
                            closure_reason TEXT NOT NULL DEFAULT '',
                            unlisted BOOLEAN NOT NULL DEFAULT 0
                        );
                    """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_tpcore_warps_category ON tpcore_warps(category);");
                    Set<String> columns = new HashSet<>();
                    try (ResultSet fields = stmt.executeQuery("PRAGMA table_info(tpcore_warps)")) {
                        while (fields.next()) columns.add(fields.getString("name"));
                    }
                    if (!columns.contains("closed")) stmt.execute("ALTER TABLE tpcore_warps ADD COLUMN closed BOOLEAN NOT NULL DEFAULT 0");
                    if (!columns.contains("closure_reason")) stmt.execute("ALTER TABLE tpcore_warps ADD COLUMN closure_reason TEXT NOT NULL DEFAULT ''");
                    if (!columns.contains("unlisted")) stmt.execute("ALTER TABLE tpcore_warps ADD COLUMN unlisted BOOLEAN NOT NULL DEFAULT 0");
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS tpcore_deleted_warps (
                            deletion_id TEXT PRIMARY KEY, warp_name TEXT NOT NULL, world_id TEXT, world_name TEXT NOT NULL,
                            x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL,
                            creator_uuid TEXT, category TEXT NOT NULL, password_hash TEXT, permission_gated BOOLEAN NOT NULL,
                            created_at BIGINT NOT NULL, closed BOOLEAN NOT NULL, closure_reason TEXT NOT NULL, unlisted BOOLEAN NOT NULL,
                            deleted_at BIGINT NOT NULL, deleted_by TEXT
                        )
                        """);
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_tpcore_deleted_warps_time ON tpcore_deleted_warps(deleted_at)");
                    pruneDeleted(conn);
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
        return virtualExecutor.submit(() -> {
            Map<String, Warp> warps = new HashMap<>();
            String sql = "SELECT warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at, closed, closure_reason, unlisted FROM tpcore_warps LIMIT " + (rowLimit + 1);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (warps.size() >= rowLimit) throw new IllegalStateException("Warp storage exceeds configured cache capacity");
                    Warp warp = mapResultSetToWarp(rs);
                    warps.put(warp.name().toLowerCase(Locale.ROOT), warp);
                }
            } catch (SQLException e) {
                logger.error("Failed to load warps from SQLite database", e);
                throw new IllegalStateException("Failed to load warp storage", e);
            }
            return warps;
        });
    }

    @Override
    public CompletableFuture<Optional<Warp>> findByName(String warpName) {
        return virtualExecutor.submit(() -> {
            String sql = "SELECT warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at, closed, closure_reason, unlisted FROM tpcore_warps WHERE warp_name = ? COLLATE NOCASE";
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
                throw new IllegalStateException("Failed to read warp storage", e);
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<List<Warp>> findByCategory(String category) {
        return virtualExecutor.submit(() -> {
            List<Warp> list = new ArrayList<>();
            String sql = "SELECT warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at, closed, closure_reason, unlisted FROM tpcore_warps WHERE category = ? COLLATE NOCASE LIMIT " + (rowLimit + 1);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, category);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (list.size() >= rowLimit) throw new IllegalStateException("Warp category exceeds configured cache capacity");
                        list.add(mapResultSetToWarp(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to find warps in category {} in SQLite database", category, e);
                throw new IllegalStateException("Failed to read warp category", e);
            }
            return list;
        });
    }

    @Override
    public CompletableFuture<Void> save(Warp warp) {
        return virtualExecutor.run(() -> {
            String sql = """
                INSERT INTO tpcore_warps (warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at, closed, closure_reason, unlisted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(warp_name) DO UPDATE SET
                    warp_name = excluded.warp_name,
                    created_at = excluded.created_at,
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
                    permission_gated = excluded.permission_gated,
                    closed = excluded.closed, closure_reason = excluded.closure_reason, unlisted = excluded.unlisted;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                bindWarp(ps, warp, 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to save warp {} to SQLite database", warp.name(), e);
                throw new RuntimeException("Database save failure for warp: " + warp.name(), e);
            }
        });
    }

    private void upsertAll(Connection conn, Collection<Warp> warps) throws SQLException {
            String sql = """
                INSERT INTO tpcore_warps (warp_name, world_id, world_name, x, y, z, yaw, pitch, creator_uuid, category, password_hash, permission_gated, created_at, closed, closure_reason, unlisted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(warp_name) DO UPDATE SET
                    warp_name = excluded.warp_name,
                    created_at = excluded.created_at,
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
                    permission_gated = excluded.permission_gated,
                    closed = excluded.closed, closure_reason = excluded.closure_reason, unlisted = excluded.unlisted;
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Warp warp : warps) { bindWarp(ps, warp, 0); ps.addBatch(); }
            ps.executeBatch();
        }
    }

    @Override public CompletableFuture<Void> saveAll(Collection<Warp> warps) {
        if (warps == null || warps.isEmpty()) return CompletableFuture.completedFuture(null);
        return virtualExecutor.run(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try { upsertAll(connection, warps); connection.commit(); }
                catch (Exception failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot save warps", failure); }
        });
    }

    @Override public CompletableFuture<Void> mergeSnapshot(Collection<Warp> warps, Collection<DeletedWarp> history) {
        return virtualExecutor.run(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    upsertAll(connection, warps);
                    try (Statement statement = connection.createStatement(); ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM tpcore_warps")) {
                        if (count.next() && count.getInt(1) > rowLimit) throw new IllegalStateException("Warp snapshot exceeds capacity");
                    }
                    long cutoff = recoveryCutoff();
                    for (DeletedWarp entry : history) if (entry.deletedAt() > cutoff) insertDeleted(connection, entry);
                    pruneDeleted(connection);
                    connection.commit();
                } catch (Exception failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot merge warp snapshot", failure); }
        });
    }

    @Override
    public CompletableFuture<Void> replace(String oldName, Warp updated) {
        return virtualExecutor.run(() -> {
            String sql = """
                UPDATE tpcore_warps SET warp_name=?, world_id=?, world_name=?, x=?, y=?, z=?, yaw=?, pitch=?,
                    creator_uuid=?, category=?, password_hash=?, permission_gated=?, created_at=?, closed=?, closure_reason=?, unlisted=?
                WHERE warp_name=? COLLATE NOCASE
                """;
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                bindWarp(statement, updated, 0);
                statement.setString(17, oldName);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Warp disappeared during edit");
            } catch (SQLException failure) { throw new IllegalStateException("Warp edit failed", failure); }
        });
    }

    @Override
    public CompletableFuture<Void> delete(String warpName) {
        return virtualExecutor.run(() -> {
            String sql = "DELETE FROM tpcore_warps WHERE warp_name = ? COLLATE NOCASE";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, warpName);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to delete warp {} from SQLite database", warpName, e);
                throw new RuntimeException("Database delete failure for warp: " + warpName, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> close() {
        return virtualExecutor.close(() -> {
            if (dataSource != null) dataSource.close();
        });
    }

    private void bindWarp(PreparedStatement statement, Warp warp, int offset) throws SQLException {
        statement.setString(offset + 1, warp.name());
        statement.setString(offset + 2, warp.worldId() == null ? null : warp.worldId().toString());
        statement.setString(offset + 3, warp.worldName());
        statement.setDouble(offset + 4, warp.x()); statement.setDouble(offset + 5, warp.y()); statement.setDouble(offset + 6, warp.z());
        statement.setFloat(offset + 7, warp.yaw()); statement.setFloat(offset + 8, warp.pitch());
        statement.setString(offset + 9, warp.creatorUuid() == null ? null : warp.creatorUuid().toString());
        statement.setString(offset + 10, warp.category()); statement.setString(offset + 11, warp.passwordHash());
        statement.setBoolean(offset + 12, warp.permissionGated()); statement.setLong(offset + 13, warp.createdAt());
        statement.setBoolean(offset + 14, warp.closed()); statement.setString(offset + 15, warp.closureReason()); statement.setBoolean(offset + 16, warp.unlisted());
    }

    private long recoveryCutoff() { return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(control.recoveryRetentionDays()); }

    private void pruneDeleted(Connection connection) throws SQLException {
        try (PreparedStatement expired = connection.prepareStatement("DELETE FROM tpcore_deleted_warps WHERE deleted_at <= ?")) {
            expired.setLong(1, recoveryCutoff()); expired.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM tpcore_deleted_warps WHERE deletion_id NOT IN (SELECT deletion_id FROM tpcore_deleted_warps ORDER BY deleted_at DESC, deletion_id DESC LIMIT " + control.recoveryMaxEntries() + ")");
        }
    }

    private DeletedWarp mapDeleted(ResultSet rows) throws SQLException {
        String actor = rows.getString("deleted_by");
        return new DeletedWarp(UUID.fromString(rows.getString("deletion_id")), mapResultSetToWarp(rows), rows.getLong("deleted_at"),
            actor == null ? null : UUID.fromString(actor));
    }

    private void insertDeleted(Connection connection, DeletedWarp entry) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO tpcore_deleted_warps (deletion_id, " + WARP_COLUMNS + ", deleted_at, deleted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, entry.id().toString()); bindWarp(insert, entry.warp(), 1);
            insert.setLong(18, entry.deletedAt()); insert.setString(19, entry.deletedBy() == null ? null : entry.deletedBy().toString());
            insert.executeUpdate();
        }
    }

    @Override public CompletableFuture<List<DeletedWarp>> loadDeleted() {
        return virtualExecutor.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                pruneDeleted(connection);
                List<DeletedWarp> entries = new ArrayList<>();
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT deletion_id, " + WARP_COLUMNS + ", deleted_at, deleted_by FROM tpcore_deleted_warps ORDER BY deleted_at DESC, deletion_id DESC LIMIT " + control.recoveryMaxEntries())) {
                    while (rows.next()) entries.add(mapDeleted(rows));
                }
                return List.copyOf(entries);
            } catch (SQLException failure) { throw new IllegalStateException("Cannot read deleted warps", failure); }
        });
    }

    @Override public CompletableFuture<Void> archiveAndDelete(DeletedWarp entry) {
        return virtualExecutor.run(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    if (entry.deletedAt() <= recoveryCutoff()) throw new IllegalStateException("Deletion snapshot expired before persistence");
                    try (PreparedStatement find = connection.prepareStatement("SELECT " + WARP_COLUMNS + " FROM tpcore_warps WHERE warp_name=? COLLATE NOCASE")) {
                        find.setString(1, entry.warp().name());
                        try (ResultSet row = find.executeQuery()) {
                            if (!row.next() || !entry.warp().equals(mapResultSetToWarp(row))) throw new IllegalStateException("Warp changed before deletion");
                        }
                    }
                    // Reject an unexpected ID collision rather than losing the only recovery snapshot.
                    try (PreparedStatement find = connection.prepareStatement("SELECT deletion_id FROM tpcore_deleted_warps WHERE deletion_id=?")) {
                        find.setString(1, entry.id().toString());
                        try (ResultSet row = find.executeQuery()) { if (row.next()) throw new IllegalStateException("Recovery ID collision"); }
                    }
                    insertDeleted(connection, entry);
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM tpcore_warps WHERE warp_name=? COLLATE NOCASE")) {
                        delete.setString(1, entry.warp().name());
                        if (delete.executeUpdate() != 1) throw new IllegalStateException("Warp disappeared during deletion");
                    }
                    pruneDeleted(connection); connection.commit();
                } catch (Exception failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot archive and delete warp", failure); }
        });
    }

    @Override public CompletableFuture<Void> restore(DeletedWarp entry) {
        return virtualExecutor.run(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    pruneDeleted(connection);
                    try (PreparedStatement find = connection.prepareStatement("SELECT deletion_id, " + WARP_COLUMNS + ", deleted_at, deleted_by FROM tpcore_deleted_warps WHERE deletion_id=?")) {
                        find.setString(1, entry.id().toString());
                        try (ResultSet row = find.executeQuery()) {
                            if (!row.next() || !entry.equals(mapDeleted(row))) throw new IllegalStateException("Recovery entry missing or changed");
                        }
                    }
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO tpcore_warps (" + WARP_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        bindWarp(insert, entry.warp(), 0); insert.executeUpdate();
                    }
                    try (PreparedStatement remove = connection.prepareStatement("DELETE FROM tpcore_deleted_warps WHERE deletion_id=?")) {
                        remove.setString(1, entry.id().toString()); remove.executeUpdate();
                    }
                    connection.commit();
                } catch (Exception failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot restore warp", failure); }
        });
    }

    @Override public CompletableFuture<Void> saveDeleted(Collection<DeletedWarp> entries) {
        return virtualExecutor.run(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    long cutoff = recoveryCutoff();
                    for (DeletedWarp entry : entries) if (entry.deletedAt() > cutoff) insertDeleted(connection, entry);
                    pruneDeleted(connection); connection.commit();
                } catch (Exception failure) { connection.rollback(); throw failure; }
                finally { connection.setAutoCommit(true); }
            } catch (SQLException failure) { throw new IllegalStateException("Cannot migrate deleted warps", failure); }
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
            createdAt,
            rs.getBoolean("closed"), rs.getString("closure_reason"), rs.getBoolean("unlisted")
        );
    }
}
