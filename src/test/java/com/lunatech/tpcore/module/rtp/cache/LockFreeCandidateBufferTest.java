package com.lunatech.tpcore.module.rtp.cache;

import com.lunatech.tpcore.module.rtp.cache.impl.LockFreeCandidateBuffer;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class LockFreeCandidateBufferTest {

    @Test
    @DisplayName("Verify power-of-two capacity normalization")
    void testCapacityNormalization() {
        LockFreeCandidateBuffer buffer = new LockFreeCandidateBuffer(20);
        Assertions.assertEquals(32, buffer.capacity());
    }

    @Test
    @DisplayName("Verify FIFO offer and poll ordering")
    void testOfferAndPoll() {
        LockFreeCandidateBuffer buffer = new LockFreeCandidateBuffer(16);

        long loc1 = PackedLocation.pack(100, 64, 200);
        long loc2 = PackedLocation.pack(300, 70, -400);

        Assertions.assertTrue(buffer.offer(loc1));
        Assertions.assertTrue(buffer.offer(loc2));
        Assertions.assertEquals(2, buffer.size());

        Assertions.assertEquals(loc1, buffer.poll());
        Assertions.assertEquals(loc2, buffer.poll());
        Assertions.assertTrue(buffer.isEmpty());
        Assertions.assertEquals(-1L, buffer.poll());
    }

    @Test
    @DisplayName("Verify buffer full rejection and clear")
    void testBufferFullAndClear() {
        LockFreeCandidateBuffer buffer = new LockFreeCandidateBuffer(4);
        int cap = buffer.capacity(); // 4

        for (int i = 0; i < cap; i++) {
            Assertions.assertTrue(buffer.offer(PackedLocation.pack(i, 64, i)));
        }

        Assertions.assertFalse(buffer.offer(PackedLocation.pack(999, 64, 999)));

        buffer.clear();
        Assertions.assertTrue(buffer.isEmpty());
        Assertions.assertEquals(0, buffer.size());
    }
}
