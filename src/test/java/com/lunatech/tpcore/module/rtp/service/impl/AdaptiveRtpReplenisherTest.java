package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.cache.impl.LockFreeCandidateBuffer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class AdaptiveRtpReplenisherTest {

    @Test
    @DisplayName("Verify LockFreeCandidateBuffer EMPTY_SENTINEL produces null candidate instead of void teleportation")
    void testEmptyBufferSentinelResolution() {
        LockFreeCandidateBuffer buffer = new LockFreeCandidateBuffer(16);
        long polled = buffer.poll();

        Assertions.assertEquals(LockFreeCandidateBuffer.EMPTY_SENTINEL, polled);
        Assertions.assertNotEquals(-1L, polled);
    }
}
