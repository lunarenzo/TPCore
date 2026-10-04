package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.model.Warp;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarpModelTest {

    @Test
    @DisplayName("Warp constructor enforces non-null invariants")
    void testConstructorInvariants() {
        UUID worldId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> new Warp(
            null, worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "general", null, false, 1000L
        ));

        assertThrows(NullPointerException.class, () -> new Warp(
            "spawn", worldId, null, 0, 64, 0, 0f, 0f, creatorId, "general", null, false, 1000L
        ));
    }

    @Test
    @DisplayName("Warp category defaults to lowercase general if null or blank")
    void testCategoryNormalization() {
        UUID worldId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        Warp w1 = new Warp("shop", worldId, "world", 10, 70, 10, 0f, 0f, creatorId, null, null, false, 1000L);
        assertEquals("general", w1.category());

        Warp w2 = new Warp("shop", worldId, "world", 10, 70, 10, 0f, 0f, creatorId, "   ", null, false, 1000L);
        assertEquals("general", w2.category());

        Warp w3 = new Warp("shop", worldId, "world", 10, 70, 10, 0f, 0f, creatorId, "VIP_ZONE", null, false, 1000L);
        assertEquals("vip_zone", w3.category());
    }

    @Test
    @DisplayName("Warp password check functions correctly")
    void testHasPassword() {
        UUID worldId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        Warp noPass = new Warp("w1", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "general", null, false, 1000L);
        assertFalse(noPass.hasPassword());

        Warp blankPass = new Warp("w2", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "general", "   ", false, 1000L);
        assertFalse(blankPass.hasPassword());

        Warp withPass = new Warp("w3", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "general", "hash123", false, 1000L);
        assertTrue(withPass.hasPassword());
    }

    @Test
    @DisplayName("withLocation creates updated copy while preserving other attributes")
    void testWithLocation() {
        UUID worldId1 = UUID.randomUUID();
        UUID worldId2 = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        Warp original = new Warp("castle", worldId1, "world", 10, 64, 20, 90f, 15f, creatorId, "builds", "hash", true, 5000L);
        Warp updated = original.withLocation("world_nether", worldId2, 100.5, 80.0, -200.5, 180f, 0f);

        assertEquals("castle", updated.name());
        assertEquals("world_nether", updated.worldName());
        assertEquals(worldId2, updated.worldId());
        assertEquals(100.5, updated.x());
        assertEquals(80.0, updated.y());
        assertEquals(-200.5, updated.z());
        assertEquals(180f, updated.yaw());
        assertEquals(0f, updated.pitch());
        assertEquals(creatorId, updated.creatorUuid());
        assertEquals("builds", updated.category());
        assertEquals("hash", updated.passwordHash());
        assertTrue(updated.permissionGated());
        assertEquals(5000L, updated.createdAt());
    }
}
