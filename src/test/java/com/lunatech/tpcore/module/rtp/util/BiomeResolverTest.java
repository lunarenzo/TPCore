package com.lunatech.tpcore.module.rtp.util;

import org.bukkit.block.Block;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class BiomeResolverTest {

    @Test
    @DisplayName("Verify BiomeResolver handles null block gracefully")
    void testNullBlockHandling() {
        String result = BiomeResolver.getBiomeKey(null);
        Assertions.assertEquals("", result);
    }

    @Test
    @DisplayName("Verify BiomeResolver extracts biome key from mocked block")
    void testMockedBlockBiomeResolution() {
        Block mockBlock = Mockito.mock(Block.class);
        String result = BiomeResolver.getBiomeKey(mockBlock);
        Assertions.assertNotNull(result);
    }
}
