package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.service.impl.WarpSafetyInspector;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class WarpSafetyInspectorTest {

    @Test
    @DisplayName("isLocationSafe correctly enforces Nether ceiling limit")
    void testNetherRoofSafety() {
        World nether = Mockito.mock(World.class);
        when(nether.getEnvironment()).thenReturn(World.Environment.NETHER);
        when(nether.getMinHeight()).thenReturn(0);
        when(nether.getMaxHeight()).thenReturn(256);

        Location locAboveCeiling = new Location(nether, 100.0, 128.0, 100.0);
        boolean safeAbove = WarpSafetyInspector.isLocationSafe(locAboveCeiling, true, 128);
        assertFalse(safeAbove, "Location at or above 128 in Nether should be unsafe when preventNetherRoof is true");

        Location locInVoid = new Location(nether, 100.0, -10.0, 100.0);
        boolean safeVoid = WarpSafetyInspector.isLocationSafe(locInVoid, true, 128);
        assertFalse(safeVoid, "Location below minHeight should be unsafe");
    }

    @Test
    @DisplayName("isLocationSafe rejects locations outside world border")
    void testWorldBorderSafety() {
        World world = Mockito.mock(World.class);
        WorldBorder border = Mockito.mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        Location locOutside = new Location(world, 10000.0, 64.0, 10000.0);
        when(border.isInside(locOutside)).thenReturn(false);

        boolean safe = WarpSafetyInspector.isLocationSafe(locOutside, false, 128);
        assertFalse(safe, "Location outside world border must be unsafe");
    }

    @Test
    @DisplayName("isLocationSafe handles null locations gracefully")
    void testNullLocationHandling() {
        assertFalse(WarpSafetyInspector.isLocationSafe(null, true, 128));
        assertFalse(WarpSafetyInspector.isLocationSafe(new Location(null, 0, 64, 0), true, 128));
    }
}
