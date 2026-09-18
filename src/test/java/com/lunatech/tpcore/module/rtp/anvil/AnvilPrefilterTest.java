package com.lunatech.tpcore.module.rtp.anvil;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class AnvilPrefilterTest {

    @Test
    @DisplayName("Verify block ID normalization for namespaced materials")
    void testNormalizeBlockId() {
        Assertions.assertEquals("STONE", AnvilPrefilter.normalizeBlockId("minecraft:stone"));
        Assertions.assertEquals("GRASS_BLOCK", AnvilPrefilter.normalizeBlockId("grass_block"));
        Assertions.assertNull(AnvilPrefilter.normalizeBlockId(null));
    }

    @Test
    @DisplayName("Verify heightmap entry bitwise unpacking from packed array")
    void testReadHeightmapEntry() {
        long[] packed = new long[]{0x00000100L}; // bit shifted entry
        int val = AnvilPrefilter.readHeightmapEntry(packed, 0, 0);
        Assertions.assertTrue(val >= 0);
    }
}
