package com.lunatech.tpcore.module.spawn;

import com.lunatech.tpcore.module.spawn.model.SpawnLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnLocationTest {

    @Test
    void testSpawnLocationCreation() {
        SpawnLocation loc = new SpawnLocation("world", 100.5, 64.0, -200.5, 90.0f, 0.0f);
        assertEquals("world", loc.worldName());
        assertEquals(100.5, loc.x());
        assertEquals(64.0, loc.y());
        assertEquals(-200.5, loc.z());
        assertEquals(90.0f, loc.yaw());
        assertEquals(0.0f, loc.pitch());
    }

    @Test
    void testFromBukkitNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> SpawnLocation.fromBukkit(null));
    }
}
