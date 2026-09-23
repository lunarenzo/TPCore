package com.lunatech.tpcore.module.tpa;

import com.lunatech.tpcore.module.tpa.service.impl.TpaSafetyInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TpaSafetyInspectorTest {

    @Test
    @DisplayName("isHazardName identifies lethal material names correctly")
    void testIsHazardName() {
        assertTrue(TpaSafetyInspector.isHazardName("LAVA"));
        assertTrue(TpaSafetyInspector.isHazardName("WATER"));
        assertTrue(TpaSafetyInspector.isHazardName("FIRE"));
        assertTrue(TpaSafetyInspector.isHazardName("SOUL_FIRE"));
        assertTrue(TpaSafetyInspector.isHazardName("MAGMA_BLOCK"));
        assertTrue(TpaSafetyInspector.isHazardName("VOID_AIR"));

        assertFalse(TpaSafetyInspector.isHazardName("STONE"));
        assertFalse(TpaSafetyInspector.isHazardName("OAK_PLANKS"));
    }

    @Test
    @DisplayName("isSolidGroundName accepts solid ground names and leaves")
    void testIsSolidGroundName() {
        assertTrue(TpaSafetyInspector.isSolidGroundName("GRASS_BLOCK"));
        assertTrue(TpaSafetyInspector.isSolidGroundName("STONE"));
        assertTrue(TpaSafetyInspector.isSolidGroundName("OAK_LEAVES"));

        assertFalse(TpaSafetyInspector.isSolidGroundName("AIR"));
        assertFalse(TpaSafetyInspector.isSolidGroundName("LAVA"));
        assertTrue(TpaSafetyInspector.isSolidGroundName("BEDROCK"));
    }

    @Test
    @DisplayName("isPassableName accepts air and light non-hazard block names")
    void testIsPassableName() {
        assertTrue(TpaSafetyInspector.isPassableName("AIR"));
        assertTrue(TpaSafetyInspector.isPassableName("CAVE_AIR"));

        assertFalse(TpaSafetyInspector.isPassableName("STONE"));
        assertFalse(TpaSafetyInspector.isPassableName("LAVA"));
    }
}
