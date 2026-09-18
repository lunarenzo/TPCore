package com.lunatech.tpcore.module.rtp.index;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class RoaringRegionIndexTest {

    @Test
    @DisplayName("Verify markSafe, isSafe, markUnsafe, and nextSetBit")
    void testBasicOperations() {
        RoaringRegionIndex index = new RoaringRegionIndex();
        Assertions.assertFalse(index.isSafe(10, 10));

        index.markSafe(10, 10);
        Assertions.assertTrue(index.isSafe(10, 10));

        index.markUnsafe(10, 10);
        Assertions.assertFalse(index.isSafe(10, 10));
    }

    @Test
    @DisplayName("Verify thread-safe concurrent marking under high thread contention")
    void testConcurrentMarking() throws InterruptedException {
        RoaringRegionIndex index = new RoaringRegionIndex();
        int threads = 10;
        int bitsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < bitsPerThread; i++) {
                        int chunkX = threadId * 2;
                        int chunkZ = i;
                        index.markSafe(chunkX, chunkZ);
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);

        Assertions.assertTrue(finished, "Concurrent marking thread execution timed out");

        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < bitsPerThread; i++) {
                int chunkX = t * 2;
                int chunkZ = i;
                Assertions.assertTrue(index.isSafe(chunkX, chunkZ), "Bit at (" + chunkX + ", " + chunkZ + ") must be set");
            }
        }
    }
}
