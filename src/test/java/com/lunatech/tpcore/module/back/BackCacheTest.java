package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.module.back.cache.impl.DefaultBackCache;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackCacheTest {

    private DefaultBackCache cache;
    private final UUID playerUuid = UUID.randomUUID();
    private final UUID worldUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cache = new DefaultBackCache();
    }

    @Test
    @DisplayName("pushLocation respects max history depth")
    void testMaxHistoryDepth() {
        for (int i = 0; i < 10; i++) {
            BackLocation loc = new BackLocation(
                worldUuid,
                "world",
                i * 10.0,
                64.0,
                i * 10.0,
                0f,
                0f,
                System.currentTimeMillis() + i,
                BackCause.TELEPORT
            );
            cache.pushLocation(playerUuid, loc, 5);
        }

        List<BackLocation> history = cache.getHistory(playerUuid);
        assertEquals(5, history.size(), "History size should be capped at maxDepth 5");

        // Most recent should be at index 0 (x = 90.0)
        assertEquals(90.0, history.get(0).x(), 0.001);
        assertEquals(50.0, history.get(4).x(), 0.001);
    }

    @Test
    @DisplayName("peekLastDeathLocation finds most recent death location")
    void testDeathLocationLookup() {
        BackLocation loc1 = new BackLocation(worldUuid, "world", 10.0, 64.0, 10.0, 0f, 0f, 1000L, BackCause.TELEPORT);
        BackLocation deathLoc = new BackLocation(worldUuid, "world", 20.0, 64.0, 20.0, 0f, 0f, 2000L, BackCause.DEATH);
        BackLocation loc2 = new BackLocation(worldUuid, "world", 30.0, 64.0, 30.0, 0f, 0f, 3000L, BackCause.TELEPORT);

        cache.pushLocation(playerUuid, loc1, 5);
        cache.pushLocation(playerUuid, deathLoc, 5);
        cache.pushLocation(playerUuid, loc2, 5);

        Optional<BackLocation> deathOpt = cache.peekLastDeathLocation(playerUuid);
        assertTrue(deathOpt.isPresent());
        assertEquals(20.0, deathOpt.get().x(), 0.001);
        assertEquals(BackCause.DEATH, deathOpt.get().cause());

        // Test popLastDeathLocation
        Optional<BackLocation> poppedDeath = cache.popLastDeathLocation(playerUuid);
        assertTrue(poppedDeath.isPresent());
        assertEquals(20.0, poppedDeath.get().x(), 0.001);

        // Subsequent death lookup should be empty
        assertFalse(cache.peekLastDeathLocation(playerUuid).isPresent());
        assertEquals(2, cache.getHistory(playerUuid).size());
    }

    @Test
    @DisplayName("removeLocationAtIndex correctly removes target element")
    void testRemoveAtIndex() {
        BackLocation loc0 = new BackLocation(worldUuid, "world", 0.0, 64.0, 0.0, 0f, 0f, 1000L, BackCause.TELEPORT);
        BackLocation loc1 = new BackLocation(worldUuid, "world", 10.0, 64.0, 10.0, 0f, 0f, 2000L, BackCause.TELEPORT);
        BackLocation loc2 = new BackLocation(worldUuid, "world", 20.0, 64.0, 20.0, 0f, 0f, 3000L, BackCause.TELEPORT);

        cache.pushLocation(playerUuid, loc0, 5);
        cache.pushLocation(playerUuid, loc1, 5);
        cache.pushLocation(playerUuid, loc2, 5);

        // Order in deque: loc2 (idx 0), loc1 (idx 1), loc0 (idx 2)
        boolean removed = cache.removeLocationAtIndex(playerUuid, 1);
        assertTrue(removed);

        List<BackLocation> history = cache.getHistory(playerUuid);
        assertEquals(2, history.size());
        assertEquals(20.0, history.get(0).x(), 0.001);
        assertEquals(0.0, history.get(1).x(), 0.001);
    }
}
