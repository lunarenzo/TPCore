package com.lunatech.tpcore.module.rtp.cache;

import com.lunatech.tpcore.module.rtp.cache.impl.LockFreeCandidateBuffer;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class LockFreeCandidateBufferConcurrencyTest {

    @Test
    @DisplayName("Verify thread safety under high concurrency (10 producers, 10 consumers)")
    void testConcurrentOfferAndPoll() throws InterruptedException {
        int bufferCapacity = 1024;
        LockFreeCandidateBuffer buffer = new LockFreeCandidateBuffer(bufferCapacity);

        int numThreads = 10;
        int opsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads * 2);
        CountDownLatch latch = new CountDownLatch(1);

        AtomicInteger totalOffered = new AtomicInteger(0);
        AtomicInteger totalPolled = new AtomicInteger(0);

        // 10 Producers
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        long loc = PackedLocation.pack(threadId * 100 + j, 64, j);
                        if (buffer.offer(loc)) {
                            totalOffered.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        // 10 Consumers
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        long polled = buffer.poll();
                        if (polled != LockFreeCandidateBuffer.EMPTY_SENTINEL) {
                            totalPolled.incrementAndGet();
                            int y = PackedLocation.unpackY(polled);
                            Assertions.assertEquals(64, y);
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        // Start all threads simultaneously
        latch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

        Assertions.assertTrue(finished, "Executor did not finish within timeout");
        Assertions.assertTrue(totalOffered.get() > 0, "No items were offered");

        // Drain remainder
        while (buffer.poll() != LockFreeCandidateBuffer.EMPTY_SENTINEL) {
            totalPolled.incrementAndGet();
        }

        Assertions.assertEquals(totalOffered.get(), totalPolled.get(), "Offered and polled counts must match exactly");
    }
}
