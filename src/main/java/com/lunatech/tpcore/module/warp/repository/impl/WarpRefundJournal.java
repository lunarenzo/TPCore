package com.lunatech.tpcore.module.warp.repository.impl;

import com.lunatech.tpcore.module.warp.util.WarpIoExecutor;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics.PaymentIntent;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics.PaymentPage;
import com.lunatech.tpcore.module.warp.model.WarpPaymentEvidence;
import java.util.Comparator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AccessDeniedException;
import java.nio.channels.FileChannel;
import java.util.Properties;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/** Payment intents are retained for reconciliation; ambiguous transfers are never replayed blindly. */
public final class WarpRefundJournal {
    private final Path path;
    private final WarpIoExecutor io;
    private final Properties entries = new Properties();
    private final int capacity;
    private final int historyLimit;
    private volatile int pending;
    private volatile int unresolved;
    private final CompletableFuture<Void> initialized;

    public WarpRefundJournal(Path path, int capacity, int queueCapacity) {
        this(path, capacity, queueCapacity, 100);
    }

    public WarpRefundJournal(Path path, int capacity, int queueCapacity, int historyLimit) {
        this.path = path;
        this.capacity = capacity;
        this.historyLimit = historyLimit;
        io = new WarpIoExecutor(queueCapacity);
        initialized = io.run(() -> {
            try {
                if (Files.exists(path)) {
                    if (Files.size(path) > capacity * 4096L + 1024) throw new IOException("Refund journal exceeds configured capacity");
                    try (InputStream input = Files.newInputStream(path)) { entries.load(input); }
                    if (entries.size() > capacity) throw new IOException("Too many pending refunds");
                    // A normal UUID/amount record is much shorter. Reject corrupt fields before exposing them in chat.
                    if (entries.entrySet().stream().anyMatch(entry -> entry.getKey().toString().length() > 256
                        || entry.getValue().toString().length() > (entry.getValue().toString().startsWith("v2|") ? 4096 : 256))) throw new IOException("Oversized warp payment evidence field");
                    for (var key : entries.stringPropertyNames()) {
                        String value = entries.getProperty(key);
                        if (value.startsWith("v2|")) entries.setProperty(key, WarpPaymentEvidence.decode(value).observed("UNKNOWN", null, null, "Recovered after restart; verify provider history").encode());
                    }
                    counts();
                }
            } catch (IOException failure) { throw new IllegalStateException("Cannot read warp refund journal", failure); }
        });
    }

    public CompletableFuture<Void> ready() { return initialized; }
    public int pending() { return pending; }
    public int unresolved() { return unresolved; }
    public int capacity() { return capacity; }
    public boolean isReady() { return initialized.isDone() && !initialized.isCompletedExceptionally(); }

    public CompletableFuture<PaymentPage> snapshot(int requestedPage, int requestedSize) {
        int size = Math.max(1, Math.min(100, requestedSize));
        if (!isReady()) return CompletableFuture.failedFuture(new IllegalStateException("Payment journal is not ready"));
        return io.submit(() -> {
            int total = entries.size();
            int pages = Math.max(1, (total + size - 1) / size);
            int page = Math.max(1, Math.min(requestedPage, pages));
            var evidence = entries.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .skip((long) (page - 1) * size).limit(size)
                .map(entry -> new PaymentIntent(entry.getKey().toString(), describe(entry.getValue().toString()))).toList();
            return new PaymentPage(page, pages, total, evidence);
        });
    }

    public CompletableFuture<UUID> record(UUID playerId, double amount) {
        return initialized.thenCompose(ignored -> io.submit(() -> {
            if (entries.size() >= capacity) throw new IllegalStateException("Refund journal capacity reached");
            UUID ticket = UUID.randomUUID();
            entries.setProperty(ticket.toString(), playerId + "|" + amount);
            try { persist(); counts(); } catch (RuntimeException failure) { entries.remove(ticket.toString()); counts(); throw failure; }
            return ticket;
        }));
    }

    private static String describe(String value) { return value.startsWith("v2|") ? WarpPaymentEvidence.decode(value).describe() : value; }
    private void counts() {
        pending = entries.size();
        unresolved = (int) entries.values().stream().map(Object::toString)
            .filter(value -> !value.startsWith("v2|") || WarpPaymentEvidence.decode(value).phase().equals("UNKNOWN")).count();
    }
    public CompletableFuture<UUID> record(WarpPaymentEvidence evidence) {
        return initialized.thenCompose(ignored -> io.submit(() -> {
            if (entries.size() >= capacity) throw new RejectedExecutionException("Payment journal capacity reached");
            UUID ticket = UUID.randomUUID(); entries.setProperty(ticket.toString(), evidence.encode());
            try { persist(); counts(); return ticket; }
            catch (RuntimeException failure) { entries.remove(ticket.toString()); counts(); throw failure; }
        }));
    }
    public CompletableFuture<Void> amend(UUID ticket, UnaryOperator<WarpPaymentEvidence> change) {
        return io.run(() -> {
            String previous = entries.getProperty(ticket.toString());
            if (previous == null) throw new IllegalStateException("Payment evidence no longer exists");
            entries.setProperty(ticket.toString(), change.apply(WarpPaymentEvidence.decode(previous)).encode());
            try { persist(); counts(); }
            catch (RuntimeException failure) { entries.setProperty(ticket.toString(), previous); counts(); throw failure; }
        });
    }
    public CompletableFuture<Void> acknowledge(String ticket, UUID actor, String note, BooleanSupplier liveTransfer) {
        if (ticket == null || ticket.length() > 256 || note == null || note.isBlank() || note.length() > 256 || actor == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("INVALID_INPUT"));
        return initialized.thenCompose(ignored -> io.run(() -> {
            String evidence = entries.getProperty(ticket);
            if (evidence == null) throw new IllegalStateException("NOT_FOUND");
            if (liveTransfer.getAsBoolean())
                throw new IllegalStateException("LIVE_TRANSFER");
            Path historyPath = path.resolveSibling("payment-resolutions.properties");
            Properties history = new Properties();
            try {
                if (Files.exists(historyPath)) {
                    if (Files.size(historyPath) > 8192000L) throw new IOException("Resolution history is oversized");
                    try (InputStream input = Files.newInputStream(historyPath)) { history.load(input); }
                    if (history.size() > 1000) throw new IOException("Resolution history is oversized");
                }
                // A fixed latest backup bounds disk use; the audit retains the full acknowledged evidence.
                Files.copy(path, path.resolveSibling("refunds.properties.pre-ack.bak"), StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel channel = FileChannel.open(path.resolveSibling("refunds.properties.pre-ack.bak"), StandardOpenOption.WRITE)) { channel.force(true); }
                String value = System.currentTimeMillis() + "|" + actor + "|" + Base64.getEncoder().encodeToString(note.getBytes(StandardCharsets.UTF_8))
                    + "|" + Base64.getEncoder().encodeToString(evidence.getBytes(StandardCharsets.UTF_8));
                history.setProperty(ticket, value);
                while (history.size() > historyLimit) {
                    String oldest = history.stringPropertyNames().stream().filter(key -> !key.equals(ticket))
                        .min(Comparator.comparingLong(key -> Long.parseLong(history.getProperty(key).split("\\|", 2)[0]))).orElseThrow();
                    history.remove(oldest);
                }
                write(historyPath, history);
                entries.remove(ticket);
                try { persist(); counts(); } catch (RuntimeException failure) { entries.setProperty(ticket, evidence); counts(); throw failure; }
            } catch (IOException failure) { throw new IllegalStateException("STORAGE", failure); }
        }));
    }

    public CompletableFuture<Void> complete(UUID ticket) {
        return io.run(() -> {
            String value = (String) entries.remove(ticket.toString());
            try { persist(); counts(); }
            catch (RuntimeException failure) { if (value != null) entries.setProperty(ticket.toString(), value); throw failure; }
        });
    }

    public CompletableFuture<Void> update(UUID ticket, UUID playerId, double amount) {
        return io.run(() -> {
            String previous = entries.getProperty(ticket.toString());
            entries.setProperty(ticket.toString(), playerId + "|" + amount);
            try { persist(); counts(); }
            catch (RuntimeException failure) { if (previous == null) entries.remove(ticket.toString()); else entries.setProperty(ticket.toString(), previous); counts(); throw failure; }
        });
    }

    private void persist() {
        write(path, entries);
    }
    private static void write(Path path, Properties entries) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) { entries.store(output, "Warp payment evidence: v2 encoded observations; legacy UUID|amount records preserved. Inspect with /warpstatus payments. Never retry ambiguous transfers."); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            replace(temporary, path);
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist warp refund journal", failure); }
    }

    /** Windows can briefly deny replacement while another process scans the file. I/O lane only; never retries a bank transfer. */
    private static void replace(Path temporary, Path target) throws IOException {
        for (int retry = 0; ; retry++) {
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return; }
            catch (AccessDeniedException denied) {
                if (retry >= 3) throw denied;
                try { Thread.sleep(25L << retry); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted replacing payment journal", interrupted); }
            }
        }
    }

    public CompletableFuture<Void> close() { return io.close(() -> { }); }
}
