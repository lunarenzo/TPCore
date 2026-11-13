package com.lunatech.tpcore.module.rtp.repository.impl;

import com.lunatech.tpcore.module.rtp.economy.RtpPaymentStatus;
import com.lunatech.tpcore.module.rtp.model.RtpPayment;
import com.lunatech.tpcore.module.rtp.model.RtpPayment.State;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

public final class SqliteRtpPaymentRepository {
    private static final String UNRESOLVED = "state NOT IN ('REJECTED','KEPT','REFUNDED','ACKNOWLEDGED')";
    private final Path file;
    private final Logger logger;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1024), Thread.ofVirtual().name("tpcore-rtp-payments-", 0).factory());
    private HikariDataSource pool;
    private volatile CompletableFuture<Void> settlement = CompletableFuture.completedFuture(null);
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final Thread hook;
    private boolean closing;
    public SqliteRtpPaymentRepository(Path file, Logger logger, int settlementDrainSeconds) {
        this.file = file; this.logger = logger;
        hook = Thread.ofPlatform().name("tpcore-rtp-payments-drain").unstarted(() -> {
            try { close().get(settlementDrainSeconds + 11L, TimeUnit.SECONDS); }
            catch (Exception failure) { logger.error("RTP payment journal drain incomplete", failure); }
        });
        Runtime.getRuntime().addShutdownHook(hook);
    }
    public CompletableFuture<Void> initialize() {
        return submit(() -> {
            Files.createDirectories(file.getParent());
            var config = new HikariConfig();
            config.setPoolName("TPCore-RtpPayments"); config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath()); config.setMaximumPoolSize(1);
            config.addDataSourceProperty("busy_timeout", "5000"); config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "FULL");
            pool = new HikariDataSource(config);
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS tpcore_rtp_payments (id TEXT PRIMARY KEY, player TEXT NOT NULL, world TEXT NOT NULL, action TEXT NOT NULL, provider TEXT NOT NULL, requested TEXT NOT NULL, debit TEXT, refund TEXT, state TEXT NOT NULL, updated INTEGER NOT NULL, operator TEXT)");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS rtp_payment_player ON tpcore_rtp_payments(player) WHERE " + UNRESOLVED);
            }
            return null;
        });
    }
    public CompletableFuture<RtpPaymentStatus> beginPayment(RtpPayment payment, int capacity) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    try (var check = connection.prepareStatement("SELECT COUNT(*), MAX(player=?) FROM tpcore_rtp_payments WHERE " + UNRESOLVED)) {
                        check.setString(1, payment.player().toString());
                        try (var rows = check.executeQuery()) {
                            if (rows.next()) {
                                if (rows.getBoolean(2)) return RtpPaymentStatus.PAYMENT_PENDING;
                                if (rows.getInt(1) >= capacity) return RtpPaymentStatus.ECONOMY_UNAVAILABLE;
                            }
                        }
                    }
                    try (var insert = connection.prepareStatement("""
                        INSERT INTO tpcore_rtp_payments (id,player,world,action,provider,requested,state,updated)
                        VALUES (?,?,?,?,?,?,'DEBIT_PENDING',?)
                        """)) {
                        insert.setString(1, payment.id().toString()); insert.setString(2, payment.player().toString());
                        insert.setString(3, payment.world()); insert.setString(4, payment.action());
                        insert.setString(5, payment.provider()); insert.setString(6, payment.requested().toPlainString());
                        insert.setLong(7, payment.updated()); insert.executeUpdate();
                    }
                    return RtpPaymentStatus.SUCCESS;
                });
            }
        });
    }

    public CompletableFuture<Boolean> updatePayment(UUID id, State expected, State next,
        BigDecimal debit, BigDecimal refund, int historyLimit) {
        return submitCritical(() -> {
            for (int attempt = 0; ; attempt++) {
                try (Connection connection = pool.getConnection()) {
                    return transaction(connection, () -> {
                        if (changePayment(connection, id, expected, next, debit, refund, historyLimit)) return true;
                        try (var query = connection.prepareStatement("SELECT state,debit,refund FROM tpcore_rtp_payments WHERE id=?")) {
                            query.setString(1, id.toString());
                            try (var rows = query.executeQuery()) {
                                return rows.next() && next.name().equals(rows.getString("state"))
                                    && (debit == null || debit.toPlainString().equals(rows.getString("debit")))
                                    && (refund == null || refund.toPlainString().equals(rows.getString("refund")));
                            }
                        }
                    });
                } catch (SQLException busy) {
                    if (attempt >= 2 || (busy.getErrorCode() != 5 && busy.getErrorCode() != 6)) throw busy;
                }
            }
        });
    }

    private static boolean changePayment(Connection connection, UUID id, State expected, State next,
        BigDecimal debit, BigDecimal refund, int historyLimit) throws SQLException {
        try (var update = connection.prepareStatement("""
            UPDATE tpcore_rtp_payments SET state=?,debit=COALESCE(?,debit),refund=COALESCE(?,refund),updated=?
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
        try (var prune = connection.prepareStatement("DELETE FROM tpcore_rtp_payments WHERE id IN ("
            + "SELECT id FROM tpcore_rtp_payments WHERE NOT (" + UNRESOLVED + ") ORDER BY updated DESC,rowid DESC LIMIT -1 OFFSET ?)")) {
            prune.setInt(1, historyLimit);
            prune.executeUpdate();
        }
    }

    public CompletableFuture<List<RtpPayment>> listPayments(long offset) {
        return submit(() -> {
            try (Connection connection = pool.getConnection();
                 var query = connection.prepareStatement("SELECT * FROM tpcore_rtp_payments ORDER BY (" + UNRESOLVED + ") DESC,updated DESC,rowid DESC LIMIT 10 OFFSET ?")) {
                query.setLong(1, offset);
                var result = new ArrayList<RtpPayment>();
                try (var rows = query.executeQuery()) { while (rows.next()) result.add(readPayment(rows)); }
                return List.copyOf(result);
            }
        });
    }

    public CompletableFuture<Optional<RtpPayment>> findPayment(UUID id) {
        return submit(() -> {
            try (Connection connection = pool.getConnection();
                 var query = connection.prepareStatement("SELECT * FROM tpcore_rtp_payments WHERE id=?")) {
                query.setString(1, id.toString());
                try (var rows = query.executeQuery()) { return rows.next() ? Optional.of(readPayment(rows)) : Optional.empty(); }
            }
        });
    }

    private static RtpPayment readPayment(ResultSet rows) throws SQLException {
        String debit = rows.getString("debit"), refund = rows.getString("refund");
        return new RtpPayment(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("player")),
            rows.getString("world"), rows.getString("action"), rows.getString("provider"),
            new BigDecimal(rows.getString("requested")), debit == null ? null : new BigDecimal(debit),
            refund == null ? null : new BigDecimal(refund), State.valueOf(rows.getString("state")),
            rows.getLong("updated"), rows.getString("operator"));
    }

    public CompletableFuture<Boolean> acknowledgePayment(UUID id, String operator, int historyLimit) {
        return submit(() -> {
            try (Connection connection = pool.getConnection()) {
                return transaction(connection, () -> {
                    try (var update = connection.prepareStatement("UPDATE tpcore_rtp_payments SET state='ACKNOWLEDGED',operator=?,updated=? WHERE id=? AND " + UNRESOLVED)) {
                        update.setString(1, operator); update.setLong(2, System.currentTimeMillis()); update.setString(3, id.toString());
                        if (update.executeUpdate() != 1) return false;
                    }
                    prunePayments(connection, historyLimit);
                    return true;
                });
            }
        });
    }

    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private static <T> T transaction(Connection connection, Work<T> work) throws Exception {
        connection.setAutoCommit(false);
        try { T value = work.run(); connection.commit(); return value; }
        catch (Exception failure) { try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); } throw failure; }
    }
    private synchronized <T> CompletableFuture<T> submit(Work<T> work) {
        if (closing) return CompletableFuture.failedFuture(new RejectedExecutionException("RTP payment journal closed"));
        var result = new CompletableFuture<T>();
        try { io.execute(() -> { try { result.complete(work.run()); } catch (Exception failure) { result.completeExceptionally(failure); } }); }
        catch (RejectedExecutionException busy) { result.completeExceptionally(busy); }
        return result;
    }
    private <T> CompletableFuture<T> submitCritical(Work<T> work) { return submit(work); }
    public void settlementDrain(CompletableFuture<Void> drain) { settlement = drain; }
    public synchronized CompletableFuture<Void> close() {
        if (!settlement.isDone()) return settlement.handle((value, failure) -> null).thenCompose(ignored -> close());
        if (closing) return stopped;
        closing = true; io.shutdown();
        Thread.ofVirtual().name("tpcore-rtp-payments-close").start(() -> {
            try {
                if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("RTP payment storage drain expired; pending evidence remains unresolved");
                    stopped.completeExceptionally(new IllegalStateException("RTP journal drain expired"));
                    while (!io.awaitTermination(1, TimeUnit.DAYS)) logger.warn("RTP payment journal is still draining");
                }
                if (pool != null) pool.close(); stopped.complete(null);
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException exiting) { /* JVM owns the hook. */ }
            } catch (Exception failure) { stopped.completeExceptionally(failure); }
        });
        return stopped;
    }
}
