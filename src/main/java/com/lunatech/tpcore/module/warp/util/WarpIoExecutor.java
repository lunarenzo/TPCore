package com.lunatech.tpcore.module.warp.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded, ordered I/O lane. Closing rejects new work and drains accepted work off-thread. */
public final class WarpIoExecutor {
    private final ThreadPoolExecutor executor;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final AtomicBoolean closing = new AtomicBoolean();

    public WarpIoExecutor(int queueCapacity) {
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
            Thread.ofVirtual().name("tpcore-warp-io-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(work.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public CompletableFuture<Void> run(Runnable work) {
        return submit(() -> { work.run(); return null; });
    }

    public CompletableFuture<Void> close(Runnable cleanup) {
        if (closing.compareAndSet(false, true)) {
            executor.shutdown();
            Thread.startVirtualThread(() -> {
                try {
                    while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        // Accepted I/O must finish before its connection or file resources close.
                    }
                    cleanup.run();
                    closed.complete(null);
                } catch (Throwable failure) {
                    closed.completeExceptionally(failure);
                }
            });
        }
        return closed;
    }
}
