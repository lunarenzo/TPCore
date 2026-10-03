package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.module.back.service.impl.BackSafetyInspector;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class BackSafetyInspectorTest {

    @Test
    @DisplayName("isLocationSafe correctly enforces Nether ceiling limit")
    void testNetherCeilingSafety() {
        World nether = Mockito.mock(World.class);
        when(nether.getEnvironment()).thenReturn(World.Environment.NETHER);
        when(nether.getMinHeight()).thenReturn(0);
        when(nether.getMaxHeight()).thenReturn(256);

        Location locAboveCeiling = new Location(nether, 100.0, 128.0, 100.0);
        boolean safeAbove = BackSafetyInspector.isLocationSafe(locAboveCeiling, true, 128);
        assertFalse(safeAbove, "Location at or above 128 in Nether should be unsafe when preventNetherRoof is true");

        Location locInVoid = new Location(nether, 100.0, -10.0, 100.0);
        boolean safeVoid = BackSafetyInspector.isLocationSafe(locInVoid, true, 128);
        assertFalse(safeVoid, "Location below minHeight should be unsafe");
    }
}
