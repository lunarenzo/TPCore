package com.lunatech.tpcore.module.rtp.index;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class RoaringRegionIndexTest {

    @Test
    @DisplayName("Verify region spatial bitmask safety flagging")
    void testMarkSafeAndUnsafe() {
        RoaringRegionIndex index = new RoaringRegionIndex();

        int chunkX = 10;
        int chunkZ = 25;

        Assertions.assertFalse(index.isSafe(chunkX, chunkZ));

        index.markSafe(chunkX, chunkZ);
        Assertions.assertTrue(index.isSafe(chunkX, chunkZ));

        index.markUnsafe(chunkX, chunkZ);
        Assertions.assertFalse(index.isSafe(chunkX, chunkZ));
    }

    @Test
    @DisplayName("Verify next safe chunk index lookup")
    void testFindNextSafeChunkIndex() {
        RoaringRegionIndex index = new RoaringRegionIndex();

        index.markSafe(5, 5); // local index rx=5, rz=5 -> 5 + 5*32 = 165

        int found = index.findNextSafeChunkIndex(0, 0, 0);
        Assertions.assertEquals(165, found);
    }
}
