package com.lunatech.tpcore.module.home.repository.impl;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.model.HomePage;
import com.lunatech.tpcore.module.home.model.HomePayment;
import com.lunatech.tpcore.module.home.model.HomePayment.State;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.math.BigDecimal;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

public final class SqliteHomeRepository implements HomeRepository {
    private static final String COLUMNS = "owner_uuid, home_name, world_name, x, y, z, yaw, pitch, created_at, shared_with, world_id";
    private static final String UNRESOLVED = "state NOT IN ('REJECTED','KEPT','REFUNDED','ACKNOWLEDGED')";
    private final File directory;
    private final Logger logger;
    private final ThreadPoolExecutor io;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final int drainSeconds;
    private final Thread shutdownHook;
    private final AtomicInteger discardedWrites = new AtomicInteger();
    private final AtomicInteger unsuccessfulWrites = new AtomicInteger();
    private final AtomicInteger committedWrites = new AtomicInteger();
    private volatile long drainDeadline;
    private final Object suggestionLock = new Object();
    private Suggestion suggestion;
    private boolean readingSuggestion;
    // Initialized and accessed on the I/O lane; closed after that lane terminates.
    private HikariDataSource pool;

    public SqliteHomeRepository(File directory, Logger logger, int queueCapacity, int drainSeconds) {
        this.directory = directory;
        this.logger = logger;
        this.drainSeconds = drainSeconds;
        io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofVirtual().name("tpcore-home-io-", 0).factory());
        shutdownHook = Thread.ofPlatform().name("tpcore-home-shutdown").unstarted(this::drainAtJvmExit);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
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
                        statement.execute("""
                            CREATE TABLE IF NOT EXISTS tpcore_home_payments (
                                id TEXT PRIMARY KEY, player TEXT NOT NULL, home TEXT NOT NULL, action TEXT NOT NULL,
                                provider TEXT NOT NULL, requested TEXT NOT NULL, debit TEXT, refund TEXT,
                                state TEXT NOT NULL, updated INTEGER NOT NULL, operator TEXT)
                            """);
                        statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_home_payment_player ON tpcore_home_payments(player) WHERE " + UNRESOLVED);
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

    private record Suggestion(UUID owner, String prefix, int limit, CompletableFuture<List<String>> result) {}

    @Override public CompletableFuture<List<String>> suggestNames(UUID owner, String prefix, int limit) {
        synchronized (suggestionLock) {
            if (closing.get()) return CompletableFuture.completedFuture(List.of());
            if (suggestion != null) {
                if (!suggestion.owner().equals(owner)) return CompletableFuture.completedFuture(List.of());
                if (readingSuggestion) return CompletableFuture.completedFuture(List.of());
                var old = suggestion;
                var latest = new Suggestion(owner, prefix, limit, new CompletableFuture<>());
                suggestion = latest;
                old.result().complete(List.of());
                return latest.result();
            }
            // Leave queue capacity for player operations; autocomplete is best effort.
            if (io.getQueue().remainingCapacity() <= 1 && io.getActiveCount() != 0)
                return CompletableFuture.completedFuture(List.of());
            var first = new Suggestion(owner, prefix, limit, new CompletableFuture<>());
            suggestion = first;
            submit(() -> {
                Suggestion current;
                synchronized (suggestionLock) { readingSuggestion = true; current = suggestion; }
                try (Connection connection = pool.getConnection();
                     var statement = connection.prepareStatement(
                         "SELECT home_name FROM tpcore_homes WHERE owner_uuid=? AND home_name LIKE ? ESCAPE '!' ORDER BY home_name LIMIT ?")) {
                    statement.setString(1, current.owner().toString());
                    statement.setString(2, current.prefix().replace("!", "!!").replace("_", "!_").replace("%", "!%") + "%");
                    statement.setInt(3, current.limit());
                    List<String> result = new ArrayList<>();
                    try (var rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
                    return List.copyOf(result);
                }
            }).whenComplete((names, error) -> {
                Suggestion current;
                synchronized (suggestionLock) {
                    current = suggestion;
                    suggestion = null;
                    readingSuggestion = false;
                }
                current.result().complete(error == null ? names : List.of());
            });
            return first.result();
        }
    }

    @Override public CompletableFuture<HomePage> page(UUID owner, long requestedPage) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    long total;
                    try (var count = connection.prepareStatement("SELECT COUNT(*) FROM tpcore_homes WHERE owner_uuid=?")) {
                        count.setString(1, owner.toString());
                        try (var rows = count.executeQuery()) {
                            if (!rows.next()) throw new SQLException("Home count query returned no row");
                            total = rows.getLong(1);
                        }
                    }
                    long page = Math.clamp(requestedPage, 1, HomePage.pageCount(total));
                    List<HomePage.Entry> entries = new ArrayList<>();
                    try (var query = connection.prepareStatement("SELECT id, " + COLUMNS
                        + " FROM tpcore_homes WHERE owner_uuid=? ORDER BY home_name LIMIT ? OFFSET ?")) {
                        query.setString(1, owner.toString());
                        query.setInt(2, HomePage.SIZE);
                        query.setLong(3, (page - 1) * HomePage.SIZE);
                        try (var rows = query.executeQuery()) {
                            while (rows.next()) entries.add(new HomePage.Entry(rows.getLong("id"), read(rows, owner)));
                        }
                    }
                    return new HomePage(total, page, entries);
                });
            }
        });
    }

    @Override public CompletableFuture<Optional<Home>> findMatching(HomePage.Entry expected) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return matching(connection, expected);
            }
        });
    }

    private static Optional<Home> matching(Connection connection, HomePage.Entry expected) throws SQLException {
        Home snapshot = expected.home();
        try (var query = connection.prepareStatement("SELECT " + COLUMNS + " FROM tpcore_homes WHERE id=? AND owner_uuid=?")) {
            query.setLong(1, expected.rowId());
            query.setString(2, snapshot.ownerUuid().toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Home current = read(rows, snapshot.ownerUuid());
                return current.equals(snapshot) ? Optional.of(current) : Optional.empty();
            }
        }
    }

    @Override public CompletableFuture<HomeResultStatus> save(Home home, boolean overwrite, int limit) {
        return submitWrite(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    boolean exists = exists(connection, home.ownerUuid(), home.name());
                    if (exists && !overwrite) return HomeResultStatus.OVERWRITE_REQUIRED;
                    if (!exists && atLimit(connection, home.ownerUuid(), limit)) return HomeResultStatus.LIMIT_REACHED;
                    return writeHome(connection, home);
                });
            }
        });
    }

    private static HomeResultStatus writeHome(Connection connection, Home home) throws SQLException {
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
    }

    @Override public CompletableFuture<SaveCheck> checkSave(Home home, boolean overwrite, int limit) {
        return submit(() -> {
            try (Connection connection = pool.getConnection();
                 var query = connection.prepareStatement("SELECT id," + COLUMNS + " FROM tpcore_homes WHERE owner_uuid=? AND home_name=?")) {
                key(query, home.ownerUuid(), home.name());
                try (var rows = query.executeQuery()) {
                    if (rows.next()) return overwrite
                        ? new SaveCheck(HomeResultStatus.SUCCESS, new HomePage.Entry(rows.getLong("id"), read(rows, home.ownerUuid())))
                        : new SaveCheck(HomeResultStatus.OVERWRITE_REQUIRED, null);
                }
                return new SaveCheck(atLimit(connection, home.ownerUuid(), limit) ? HomeResultStatus.LIMIT_REACHED : HomeResultStatus.SUCCESS, null);
            }
        });
    }

    @Override public CompletableFuture<HomeResultStatus> saveChecked(Home home, HomePage.Entry expected, int limit, UUID payment, int historyLimit) {
        return submitWrite(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    if (expected != null) {
                        if (!expected.home().ownerUuid().equals(home.ownerUuid()) || !expected.home().name().equalsIgnoreCase(home.name())
                            || matching(connection, expected).isEmpty()) return HomeResultStatus.HOME_CHANGED;
                    } else {
                        if (exists(connection, home.ownerUuid(), home.name())) return HomeResultStatus.HOME_CHANGED;
                        if (atLimit(connection, home.ownerUuid(), limit)) return HomeResultStatus.LIMIT_REACHED;
                    }
                    if (payment != null) {
                        try (var check = connection.prepareStatement("""
                            SELECT 1 FROM tpcore_home_payments
                            WHERE id=? AND player=? AND home=? AND action='CREATE' AND state='DEBITED'
                            """)) {
                            check.setString(1, payment.toString());
                            check.setString(2, home.ownerUuid().toString());
                            check.setString(3, home.name());
                            try (var rows = check.executeQuery()) {
                                if (!rows.next()) return HomeResultStatus.PAYMENT_PENDING;
                            }
                        }
                        if (expected != null || !changePayment(connection, payment, State.DEBITED, State.KEPT, null, null, historyLimit))
                            return HomeResultStatus.PAYMENT_PENDING;
                    }
                    return writeHome(connection, home);
                });
            }
        });
    }

    @Override public CompletableFuture<HomeResultStatus> beginPayment(HomePayment payment, int capacity) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    try (var check = connection.prepareStatement("SELECT COUNT(*), MAX(player=?) FROM tpcore_home_payments WHERE " + UNRESOLVED)) {
                        check.setString(1, payment.player().toString());
                        try (var rows = check.executeQuery()) {
                            if (rows.next()) {
                                if (rows.getBoolean(2)) return HomeResultStatus.PAYMENT_PENDING;
                                if (rows.getInt(1) >= capacity) return HomeResultStatus.ECONOMY_UNAVAILABLE;
                            }
                        }
                    }
                    try (var insert = connection.prepareStatement("""
                        INSERT INTO tpcore_home_payments (id,player,home,action,provider,requested,state,updated)
                        VALUES (?,?,?,?,?,?,'DEBIT_PENDING',?)
                        """)) {
                        insert.setString(1, payment.id().toString()); insert.setString(2, payment.player().toString());
                        insert.setString(3, payment.home()); insert.setString(4, payment.action().name());
                        insert.setString(5, payment.provider()); insert.setString(6, payment.requested().toPlainString());
                        insert.setLong(7, payment.updated()); insert.executeUpdate();
                    }
                    return HomeResultStatus.SUCCESS;
                });
            }
        });
    }

    @Override public CompletableFuture<Boolean> updatePayment(UUID id, State expected, State next,
        BigDecimal debit, BigDecimal refund, int historyLimit) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> changePayment(connection, id, expected, next, debit, refund, historyLimit));
            }
        });
    }

    private static boolean changePayment(Connection connection, UUID id, State expected, State next,
        BigDecimal debit, BigDecimal refund, int historyLimit) throws SQLException {
        try (var update = connection.prepareStatement("""
            UPDATE tpcore_home_payments SET state=?,debit=COALESCE(?,debit),refund=COALESCE(?,refund),updated=?
            WHERE id=? AND state=?
            """)) {
            update.setString(1, next.name()); update.setString(2, debit == null ? null : debit.toPlainString());
            update.setString(3, refund == null ? null : refund.toPlainString());
            update.setLong(4, System.currentTimeMillis()); update.setString(5, id.toString()); update.setString(6, expected.name());
            if (update.executeUpdate() != 1) return false;
        }
        if (next.resolved()) prunePayments(connection, historyLimit);
        return true;
    }

    private static void prunePayments(Connection connection, int historyLimit) throws SQLException {
        try (var prune = connection.prepareStatement("DELETE FROM tpcore_home_payments WHERE id IN ("
            + "SELECT id FROM tpcore_home_payments WHERE NOT (" + UNRESOLVED + ") ORDER BY updated DESC,rowid DESC LIMIT -1 OFFSET ?)")) {
            prune.setInt(1, historyLimit);
            prune.executeUpdate();
        }
    }

    @Override public CompletableFuture<List<HomePayment>> listPayments(long offset) {
        return submit(() -> {
            try (Connection connection = pool.getConnection();
                 var query = connection.prepareStatement("SELECT * FROM tpcore_home_payments ORDER BY (" + UNRESOLVED + ") DESC,updated DESC,rowid DESC LIMIT 10 OFFSET ?")) {
                query.setLong(1, offset);
                var result = new ArrayList<HomePayment>();
                try (var rows = query.executeQuery()) { while (rows.next()) result.add(readPayment(rows)); }
                return List.copyOf(result);
            }
        });
    }

    @Override public CompletableFuture<Optional<HomePayment>> findPayment(UUID id) {
        return submit(() -> {
            try (Connection connection = pool.getConnection();
                 var query = connection.prepareStatement("SELECT * FROM tpcore_home_payments WHERE id=?")) {
                query.setString(1, id.toString());
                try (var rows = query.executeQuery()) { return rows.next() ? Optional.of(readPayment(rows)) : Optional.empty(); }
            }
        });
    }

    private static HomePayment readPayment(ResultSet rows) throws SQLException {
        String debit = rows.getString("debit"), refund = rows.getString("refund");
        return new HomePayment(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("player")),
            rows.getString("home"), HomePayment.Action.valueOf(rows.getString("action")), rows.getString("provider"),
            new BigDecimal(rows.getString("requested")), debit == null ? null : new BigDecimal(debit),
            refund == null ? null : new BigDecimal(refund), State.valueOf(rows.getString("state")),
            rows.getLong("updated"), rows.getString("operator"));
    }

    @Override public CompletableFuture<Boolean> acknowledgePayment(UUID id, String operator, int historyLimit) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    try (var update = connection.prepareStatement("UPDATE tpcore_home_payments SET state='ACKNOWLEDGED',operator=?,updated=? WHERE id=? AND " + UNRESOLVED)) {
                        update.setString(1, operator); update.setLong(2, System.currentTimeMillis()); update.setString(3, id.toString());
                        if (update.executeUpdate() != 1) return false;
                    }
                    prunePayments(connection, historyLimit);
                    return true;
                });
            }
        });
    }

    @Override public CompletableFuture<HomeResultStatus> archive(UUID owner, String name, int capacity, long retentionMillis) {
        return archive(owner, name, null, capacity, retentionMillis);
    }

    @Override public CompletableFuture<HomeResultStatus> archiveMatching(HomePage.Entry expected, int capacity, long retentionMillis) {
        return archive(expected.home().ownerUuid(), expected.home().name(), expected, capacity, retentionMillis);
    }

    private CompletableFuture<HomeResultStatus> archive(UUID owner, String name, HomePage.Entry expected, int capacity, long retentionMillis) {
        return submitWrite(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    if (expected != null && matching(connection, expected).isEmpty()) return HomeResultStatus.HOME_CHANGED;
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
        return submitWrite(() -> {
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
        return submit(work, false);
    }

    private CompletableFuture<HomeResultStatus> submitWrite(Work<HomeResultStatus> work) {
        return submit(work, true);
    }

    private <T> CompletableFuture<T> submit(Work<T> work, boolean write) {
        if (closing.get()) return CompletableFuture.failedFuture(new RejectedExecutionException("Home storage is closing"));
        var task = new StorageTask<>(work, write);
        try { io.execute(task); }
        catch (RejectedExecutionException rejected) { task.result.completeExceptionally(rejected); }
        return task.result;
    }

    private final class StorageTask<T> implements Runnable {
        private final Work<T> work;
        private final boolean write;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        StorageTask(Work<T> work, boolean write) { this.work = work; this.write = write; }

        void discard() {
            if (write) discardedWrites.incrementAndGet();
            result.completeExceptionally(new RejectedExecutionException("Home storage drain deadline expired before execution"));
        }

        @Override public void run() {
            long deadline = drainDeadline;
            if (deadline != 0 && System.nanoTime() - deadline >= 0) { discard(); return; }
            try {
                T value = work.run();
                if (write && closing.get()) {
                    if (value == HomeResultStatus.SUCCESS) committedWrites.incrementAndGet();
                    else unsuccessfulWrites.incrementAndGet();
                }
                result.complete(value);
            } catch (Exception failure) {
                if (write && closing.get()) unsuccessfulWrites.incrementAndGet();
                result.completeExceptionally(failure);
            }
        }
    }

    @Override public CompletableFuture<Void> close() {
        if (closing.compareAndSet(false, true)) {
            drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(drainSeconds);
            io.shutdown();
            Thread.startVirtualThread(() -> {
                try {
                    if (!io.awaitTermination(Math.max(0, drainDeadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        var discarded = new ArrayList<Runnable>();
                        io.getQueue().drainTo(discarded);
                        for (Runnable item : discarded) ((StorageTask<?>) item).discard();
                        logger.warn("Home storage drain deadline expired: {} queued writes dropped; any running operation is still unresolved",
                            discardedWrites.get());
                        // Do not close a connection while an in-flight transaction owns it.
                        io.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                    }
                    if (pool != null) pool.close();
                    logger.info("Home storage drain complete: {} writes committed, {} failed or rejected by policy, {} discarded",
                        committedWrites.get(), unsuccessfulWrites.get(), discardedWrites.get());
                    stopped.complete(null);
                } catch (Exception failure) { stopped.completeExceptionally(failure); }
                finally {
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                    catch (IllegalStateException shuttingDown) { /* The JVM already started the hook. */ }
                }
            });
        }
        return stopped;
    }

    private void drainAtJvmExit() {
        try { close().get(drainSeconds + 1L, TimeUnit.SECONDS); }
        catch (Exception incomplete) {
            logger.error("Home storage did not finish before JVM exit: {} writes committed, {} discarded; an in-flight write may be unresolved",
                committedWrites.get(), discardedWrites.get(), incomplete);
        }
    }

    @FunctionalInterface
    private interface Work<T> { T run() throws Exception; }
}
