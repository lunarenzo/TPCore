package com.lunatech.tpcore.module.home.repository.impl;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

public final class SqliteHomeRepository implements HomeRepository {
    private static final String COLUMNS = "owner_uuid, home_name, world_name, x, y, z, yaw, pitch, created_at, shared_with, world_id";
    private final File directory;
    private final Logger logger;
    private final ThreadPoolExecutor io;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    // Initialized and accessed on the I/O lane; closed after that lane terminates.
    private HikariDataSource pool;

    public SqliteHomeRepository(File directory, Logger logger, int queueCapacity) {
        this.directory = directory;
        this.logger = logger;
        io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofVirtual().name("tpcore-home-io-", 0).factory());
    }

    @Override public CompletableFuture<Void> initialize() {
        return submit(() -> {
            Files.createDirectories(directory.toPath());
            HikariConfig config = new HikariConfig();
            config.setPoolName("TPCore-HomeSQLitePool");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + new File(directory, "tpcore.db").getAbsolutePath());
            config.setMaximumPoolSize(1);
            config.setConnectionTimeout(5000);
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("busy_timeout", "5000");
            config.addDataSourceProperty("synchronous", "FULL");
            pool = new HikariDataSource(config);
            try (Connection connection = pool.getConnection()) {
                transaction(connection, () -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("""
                            CREATE TABLE IF NOT EXISTS tpcore_homes (
                                id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL,
                                home_name TEXT COLLATE NOCASE NOT NULL, world_name TEXT NOT NULL,
                                x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL,
                                pitch FLOAT NOT NULL, created_at BIGINT NOT NULL, shared_with TEXT DEFAULT '',
                                world_id TEXT, UNIQUE(owner_uuid, home_name))
                            """);
                        boolean hasWorldId = false;
                        try (var rows = statement.executeQuery("PRAGMA table_info(tpcore_homes)")) {
                            while (rows.next()) hasWorldId |= "world_id".equalsIgnoreCase(rows.getString("name"));
                        }
                        if (!hasWorldId) statement.execute("ALTER TABLE tpcore_homes ADD COLUMN world_id TEXT");
                        statement.execute("""
                            CREATE TABLE IF NOT EXISTS tpcore_home_recovery (
                                owner_uuid TEXT NOT NULL, home_name TEXT COLLATE NOCASE NOT NULL, world_name TEXT NOT NULL,
                                x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL,
                                pitch FLOAT NOT NULL, created_at BIGINT NOT NULL, shared_with TEXT, world_id TEXT,
                                deleted_at BIGINT NOT NULL, PRIMARY KEY(owner_uuid, home_name))
                            """);
                        statement.execute("CREATE INDEX IF NOT EXISTS idx_home_recovery_age ON tpcore_home_recovery(deleted_at)");
                    }
                    return null;
                });
            } catch (Exception failure) {
                pool.close();
                pool = null;
                throw failure;
            }
            return null;
        });
    }

    @Override public CompletableFuture<Optional<Home>> findByName(UUID owner, String name) {
        return submit(() -> {
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM tpcore_homes WHERE owner_uuid=? AND home_name=?")) {
                key(statement, owner, name);
                try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(read(rows, owner)) : Optional.empty(); }
            }
        });
    }

    @Override public CompletableFuture<List<Home>> list(UUID owner, int limit, long offset) {
        return submit(() -> {
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM tpcore_homes WHERE owner_uuid=? ORDER BY home_name LIMIT ? OFFSET ?")) {
                statement.setString(1, owner.toString());
                statement.setInt(2, limit);
                statement.setLong(3, offset);
                List<Home> result = new ArrayList<>();
                try (var rows = statement.executeQuery()) { while (rows.next()) result.add(read(rows, owner)); }
                return List.copyOf(result);
            }
        });
    }

    @Override public CompletableFuture<HomeResultStatus> save(Home home, boolean overwrite, int limit) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    boolean exists = exists(connection, home.ownerUuid(), home.name());
                    if (exists && !overwrite) return HomeResultStatus.OVERWRITE_REQUIRED;
                    if (!exists && atLimit(connection, home.ownerUuid(), limit)) return HomeResultStatus.LIMIT_REACHED;
                    try (var statement = connection.prepareStatement("""
                        INSERT INTO tpcore_homes (owner_uuid,home_name,world_name,x,y,z,yaw,pitch,created_at,world_id)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(owner_uuid,home_name) DO UPDATE SET world_name=excluded.world_name,
                        x=excluded.x,y=excluded.y,z=excluded.z,yaw=excluded.yaw,pitch=excluded.pitch,world_id=excluded.world_id
                        """)) {
                        key(statement, home.ownerUuid(), home.name());
                        statement.setString(3, home.worldName());
                        statement.setDouble(4, home.x()); statement.setDouble(5, home.y()); statement.setDouble(6, home.z());
                        statement.setFloat(7, home.yaw()); statement.setFloat(8, home.pitch());
                        statement.setLong(9, home.createdAt()); statement.setString(10, home.worldId() == null ? null : home.worldId().toString());
                        statement.executeUpdate();
                    }
                    return HomeResultStatus.SUCCESS;
                });
            }
        });
    }

    @Override public CompletableFuture<HomeResultStatus> archive(UUID owner, String name, int capacity, long retentionMillis) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    long now = System.currentTimeMillis();
                    try (var copy = connection.prepareStatement("INSERT OR REPLACE INTO tpcore_home_recovery (" + COLUMNS
                        + ", deleted_at) SELECT " + COLUMNS + ", ? FROM tpcore_homes WHERE owner_uuid=? AND home_name=?")) {
                        copy.setLong(1, now);
                        copy.setString(2, owner.toString()); copy.setString(3, name);
                        if (copy.executeUpdate() == 0) return HomeResultStatus.HOME_NOT_FOUND;
                    }
                    try (var delete = connection.prepareStatement("DELETE FROM tpcore_homes WHERE owner_uuid=? AND home_name=?")) {
                        key(delete, owner, name); delete.executeUpdate();
                    }
                    try (var trim = connection.prepareStatement("""
                        DELETE FROM tpcore_home_recovery WHERE deleted_at<? OR rowid IN (
                            SELECT rowid FROM tpcore_home_recovery ORDER BY deleted_at DESC,rowid DESC LIMIT -1 OFFSET ?)
                        """)) {
                        trim.setLong(1, now - retentionMillis); trim.setInt(2, capacity); trim.executeUpdate();
                    }
                    return HomeResultStatus.SUCCESS;
                });
            }
        });
    }

    @Override public CompletableFuture<HomeResultStatus> restore(UUID owner, String name, int limit, long retentionMillis) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    if (exists(connection, owner, name)) return HomeResultStatus.OVERWRITE_REQUIRED;
                    if (atLimit(connection, owner, limit)) return HomeResultStatus.LIMIT_REACHED;
                    try (var copy = connection.prepareStatement("INSERT INTO tpcore_homes (" + COLUMNS + ") SELECT "
                        + COLUMNS + " FROM tpcore_home_recovery WHERE owner_uuid=? AND home_name=? AND deleted_at>=?")) {
                        key(copy, owner, name); copy.setLong(3, System.currentTimeMillis() - retentionMillis);
                        if (copy.executeUpdate() == 0) return HomeResultStatus.RECOVERY_NOT_FOUND;
                    }
                    try (var delete = connection.prepareStatement("DELETE FROM tpcore_home_recovery WHERE owner_uuid=? AND home_name=?")) {
                        key(delete, owner, name); delete.executeUpdate();
                    }
                    return HomeResultStatus.SUCCESS;
                });
            }
        });
    }

    private static boolean exists(Connection connection, UUID owner, String name) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM tpcore_homes WHERE owner_uuid=? AND home_name=?")) {
            key(statement, owner, name);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static boolean atLimit(Connection connection, UUID owner, int limit) throws SQLException {
        if (limit <= 0) return true;
        if (limit == Integer.MAX_VALUE) return false;
        try (var statement = connection.prepareStatement("SELECT 1 FROM tpcore_homes WHERE owner_uuid=? LIMIT 1 OFFSET ?")) {
            statement.setString(1, owner.toString()); statement.setInt(2, limit - 1);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static Home read(ResultSet row, UUID owner) throws SQLException {
        String worldId = row.getString("world_id");
        return new Home(owner, row.getString("home_name"), row.getString("world_name"), worldId == null ? null : UUID.fromString(worldId),
            row.getDouble("x"), row.getDouble("y"), row.getDouble("z"), row.getFloat("yaw"), row.getFloat("pitch"), row.getLong("created_at"));
    }

    private static void key(PreparedStatement statement, UUID owner, String name) throws SQLException {
        statement.setString(1, owner.toString()); statement.setString(2, name);
    }

    private static <T> T transaction(Connection connection, Work<T> work) throws Exception {
        connection.setAutoCommit(false);
        try {
            T value = work.run();
            connection.commit();
            return value;
        } catch (Exception failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private <T> CompletableFuture<T> submit(Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try { result.complete(work.run()); }
                catch (Exception failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        return result;
    }

    @Override public CompletableFuture<Void> close() {
        if (closing.compareAndSet(false, true)) {
            io.shutdown();
            Thread.startVirtualThread(() -> {
                try {
                    if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                        logger.warn("Home storage is still draining; keeping its pool open until accepted writes finish");
                        while (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                            // Keep accepted writes intact and prevent a replacement lane from opening.
                        }
                    }
                    if (pool != null) pool.close();
                    stopped.complete(null);
                } catch (Exception failure) { stopped.completeExceptionally(failure); }
            });
        }
        return stopped;
    }

    @FunctionalInterface
    private interface Work<T> { T run() throws Exception; }
}
