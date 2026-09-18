package com.lunatech.tpcore.module.rtp.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class PackedLocationTest {

    @Test
    @DisplayName("Verify primitive long packing and unpacking for positive coordinates")
    void testPositiveCoordinates() {
        int x = 1250;
        int y = 72;
        int z = -4300;

        long packed = PackedLocation.pack(x, y, z);

        Assertions.assertEquals(x, PackedLocation.unpackX(packed));
        Assertions.assertEquals(y, PackedLocation.unpackY(packed));
        Assertions.assertEquals(z, PackedLocation.unpackZ(packed));
    }

    @Test
    @DisplayName("Verify boundary coordinate bitpacking at extreme world borders")
    void testBoundaryCoordinates() {
        int minX = -30_000_000;
        int minY = -64;
        int minZ = -30_000_000;

        long packedMin = PackedLocation.pack(minX, minY, minZ);

        Assertions.assertEquals(minX, PackedLocation.unpackX(packedMin));
        Assertions.assertEquals(minY, PackedLocation.unpackY(packedMin));
        Assertions.assertEquals(minZ, PackedLocation.unpackZ(packedMin));

        int maxX = 30_000_000;
        int maxY = 319;
        int maxZ = 30_000_000;

        long packedMax = PackedLocation.pack(maxX, maxY, maxZ);

        Assertions.assertEquals(maxX, PackedLocation.unpackX(packedMax));
        Assertions.assertEquals(maxY, PackedLocation.unpackY(packedMax));
        Assertions.assertEquals(maxZ, PackedLocation.unpackZ(packedMax));
    }

    @Test
    @DisplayName("Verify zero Y coordinate and origin handling")
    void testOriginCoordinates() {
        long packedOrigin = PackedLocation.pack(0, 0, 0);

        Assertions.assertEquals(0, PackedLocation.unpackX(packedOrigin));
        Assertions.assertEquals(0, PackedLocation.unpackY(packedOrigin));
        Assertions.assertEquals(0, PackedLocation.unpackZ(packedOrigin));
    }
}
