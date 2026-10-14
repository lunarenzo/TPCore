package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.service.impl.WarpSafetyInspector;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

class WarpSafetyInspectorTest {

    @Test
    void failedNearbySurfaceSearchMustNotUseAnUnrelatedSafeLocationAtYZero() {
        World world = Mockito.mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        Block water = Mockito.mock(Block.class);
        when(water.getType()).thenReturn(Material.WATER);
        Block air = Mockito.mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(0, -1, 0)).thenReturn(water);
        when(world.getBlockAt(0, 0, 0)).thenReturn(air);
        when(world.getBlockAt(0, 1, 0)).thenReturn(air);

        try (var bukkit = Mockito.mockStatic(Bukkit.class)) {
            assertNull(WarpSafetyInspector.inspectLocation(new Location(world, 0, 100, 0), true, 3, false, 128));
        }
    }

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
