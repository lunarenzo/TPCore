package com.lunatech.tpcore.module.spawn;

import com.lunatech.tpcore.module.spawn.service.impl.SpawnSafetyInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnSafetyInspectorTest {

    @Test
    @DisplayName("isHazardName correctly identifies dangerous materials")
    void testIsHazardName() {
        assertTrue(SpawnSafetyInspector.isHazardName("LAVA"));
        assertTrue(SpawnSafetyInspector.isHazardName("FIRE"));
        assertTrue(SpawnSafetyInspector.isHazardName("SOUL_FIRE"));
        assertTrue(SpawnSafetyInspector.isHazardName("MAGMA_BLOCK"));
        assertTrue(SpawnSafetyInspector.isHazardName("VOID_AIR"));
        assertTrue(SpawnSafetyInspector.isHazardName("SWEET_BERRY_BUSH"));
        assertTrue(SpawnSafetyInspector.isHazardName("WITHER_ROSE"));
        assertTrue(SpawnSafetyInspector.isHazardName("POWDER_SNOW"));

        assertFalse(SpawnSafetyInspector.isHazardName("STONE"));
        assertFalse(SpawnSafetyInspector.isHazardName("DIRT"));
        assertFalse(SpawnSafetyInspector.isHazardName("OAK_PLANKS"));
        assertFalse(SpawnSafetyInspector.isHazardName("AIR"));
    }

    @Test
    @DisplayName("isSolidGroundName validates solid blocks and rejects hazards/air")
    void testIsSolidGroundName() {
        assertTrue(SpawnSafetyInspector.isSolidGroundName("STONE"));
        assertTrue(SpawnSafetyInspector.isSolidGroundName("GRASS_BLOCK"));
        assertTrue(SpawnSafetyInspector.isSolidGroundName("BEDROCK"));
        assertTrue(SpawnSafetyInspector.isSolidGroundName("OAK_PLANKS"));

        assertFalse(SpawnSafetyInspector.isSolidGroundName("AIR"));
        assertFalse(SpawnSafetyInspector.isSolidGroundName("LAVA"));
        assertFalse(SpawnSafetyInspector.isSolidGroundName("FIRE"));
    }

    @Test
    @DisplayName("isPassableName accepts air and transparent non-hazard blocks")
    void testIsPassableName() {
        assertTrue(SpawnSafetyInspector.isPassableName("AIR"));
        assertTrue(SpawnSafetyInspector.isPassableName("CAVE_AIR"));
        assertTrue(SpawnSafetyInspector.isPassableName("LIGHT"));

        assertFalse(SpawnSafetyInspector.isPassableName("STONE"));
        assertFalse(SpawnSafetyInspector.isPassableName("LAVA"));
        assertFalse(SpawnSafetyInspector.isPassableName("WATER"));
    }
}
