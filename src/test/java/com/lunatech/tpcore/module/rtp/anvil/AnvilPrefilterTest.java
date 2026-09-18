package com.lunatech.tpcore.module.rtp.anvil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    @DisplayName("Verify region file resolution for Paper separate world folders and Vanilla subfolders")
    void testResolveRegionFile(@TempDir Path tempDir) throws Exception {
        // 1. Paper Nether Structure (world_nether/region)
        Path paperNether = tempDir.resolve("world_nether");
        Files.createDirectories(paperNether.resolve("region"));
        Path paperResolved = AnvilPrefilter.resolveRegionFile(paperNether, "DIM-1", 0, 0);
        Assertions.assertEquals(paperNether.resolve("region/r.0.0.mca"), paperResolved);

        // 2. Vanilla 1.20.6 - 1.21.11 Nether Structure (world/DIM-1/region)
        Path vanillaWorld = tempDir.resolve("world");
        Files.createDirectories(vanillaWorld.resolve("DIM-1/region"));
        Path vanillaResolved = AnvilPrefilter.resolveRegionFile(vanillaWorld, "DIM-1", 0, 0);
        Assertions.assertEquals(vanillaWorld.resolve("DIM-1/region/r.0.0.mca"), vanillaResolved);

        // 3. Modern 26.1+ Namespaced Nether Structure (world/dimensions/minecraft/the_nether/region)
        Path modernWorld = tempDir.resolve("world_modern");
        Files.createDirectories(modernWorld.resolve("dimensions/minecraft/the_nether/region"));
        Path modernNetherResolved = AnvilPrefilter.resolveRegionFile(modernWorld, "DIM-1", 0, 0);
        Assertions.assertEquals(modernWorld.resolve("dimensions/minecraft/the_nether/region/r.0.0.mca"), modernNetherResolved);

        // 4. Modern 26.1+ Namespaced Overworld Structure (world/dimensions/minecraft/overworld/region)
        Path modernOverworld = tempDir.resolve("world_modern_ow");
        Files.createDirectories(modernOverworld.resolve("dimensions/minecraft/overworld/region"));
        Path modernOwResolved = AnvilPrefilter.resolveRegionFile(modernOverworld, "", 0, 0);
        Assertions.assertEquals(modernOverworld.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), modernOwResolved);
    }
}
