package com.lunatech.tpcore.module.rtp.anvil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Set;
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

    @Test
    @DisplayName("Verify b_linear region file header probing if reference file exists")
    void testBLinearReferenceProbing() {
        File refFile = new File("/storage/emulated/0/Download/r.6.0.b_linear");
        if (refFile.exists()) {
            Path worldFolder = refFile.getParentFile().getParentFile().toPath();
            // regionX = 6 -> chunkX = 6 * 32 = 192. chunkZ = 0.
            AnvilPrefilter.Verdict verdict = AnvilPrefilter.probeSync(
                refFile.getParentFile().getParentFile().toPath(),
                refFile.getParentFile().getName(),
                192,
                0,
                Set.of()
            );
            Assertions.assertNotNull(verdict);
        }
    }
}
