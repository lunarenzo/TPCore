package com.lunatech.tpcore.module.warp.repository.impl;

import com.lunatech.tpcore.module.warp.util.WarpIoExecutor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Payment intents are retained for reconciliation; ambiguous transfers are never replayed blindly. */
public final class WarpRefundJournal {
    private final Path path;
    private final WarpIoExecutor io;
    private final Properties entries = new Properties();
    private final int capacity;
    private volatile int pending;
    private final CompletableFuture<Void> initialized;

    public WarpRefundJournal(Path path, int capacity, int queueCapacity) {
        this.path = path;
        this.capacity = capacity;
        io = new WarpIoExecutor(queueCapacity);
        initialized = io.run(() -> {
            try {
                if (Files.exists(path)) {
                    if (Files.size(path) > capacity * 256L) throw new IOException("Refund journal exceeds configured capacity");
                    try (InputStream input = Files.newInputStream(path)) { entries.load(input); }
                    if (entries.size() > capacity) throw new IOException("Too many pending refunds");
                    pending = entries.size();
                }
            } catch (IOException failure) { throw new IllegalStateException("Cannot read warp refund journal", failure); }
        });
    }

    public CompletableFuture<Void> ready() { return initialized; }
    public int pending() { return pending; }

    public CompletableFuture<UUID> record(UUID playerId, double amount) {
        return initialized.thenCompose(ignored -> io.submit(() -> {
            if (entries.size() >= capacity) throw new IllegalStateException("Refund journal capacity reached");
            UUID ticket = UUID.randomUUID();
            entries.setProperty(ticket.toString(), playerId + "|" + amount);
            pending = entries.size();
            persist();
            return ticket;
        }));
    }

    public CompletableFuture<Void> complete(UUID ticket) {
        return io.run(() -> {
            String value = (String) entries.remove(ticket.toString());
            try { persist(); pending = entries.size(); }
            catch (RuntimeException failure) { if (value != null) entries.setProperty(ticket.toString(), value); throw failure; }
        });
    }

    public CompletableFuture<Void> update(UUID ticket, UUID playerId, double amount) {
        return io.run(() -> {
            entries.setProperty(ticket.toString(), playerId + "|" + amount);
            persist();
        });
    }

    private void persist() {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) { entries.store(output, "Unresolved warp payment intents: transaction = player UUID | amount. Reconcile debit and credit history before removing."); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist warp refund journal", failure); }
    }

    public CompletableFuture<Void> close() { return io.close(() -> { }); }
}
