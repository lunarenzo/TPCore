package com.lunatech.tpcore.module.spawn;

import com.lunatech.tpcore.module.spawn.service.impl.SpawnCooldownManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpawnCooldownManagerTest {

    private SpawnCooldownManager cooldownManager;
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        this.cooldownManager = new SpawnCooldownManager();
    }

    @Test
    void testCooldownFlow() {
        assertFalse(this.cooldownManager.isOnCooldown(this.playerId, 10));
        assertEquals(0L, this.cooldownManager.getRemainingCooldownMs(this.playerId, 10));

        this.cooldownManager.applyCooldown(this.playerId);

        assertTrue(this.cooldownManager.isOnCooldown(this.playerId, 10));
        assertTrue(this.cooldownManager.getRemainingCooldownMs(this.playerId, 10) > 0);

        this.cooldownManager.removeCooldown(this.playerId);
        assertFalse(this.cooldownManager.isOnCooldown(this.playerId, 10));
    }

    @Test
    void testZeroCooldownDisabled() {
        this.cooldownManager.applyCooldown(this.playerId);
        assertFalse(this.cooldownManager.isOnCooldown(this.playerId, 0));
        assertEquals(0L, this.cooldownManager.getRemainingCooldownMs(this.playerId, 0));
    }

    @Test
    void testClearAll() {
        this.cooldownManager.applyCooldown(this.playerId);
        UUID player2 = UUID.randomUUID();
        this.cooldownManager.applyCooldown(player2);

        this.cooldownManager.clear();
        assertFalse(this.cooldownManager.isOnCooldown(this.playerId, 10));
        assertFalse(this.cooldownManager.isOnCooldown(player2, 10));
    }
}
