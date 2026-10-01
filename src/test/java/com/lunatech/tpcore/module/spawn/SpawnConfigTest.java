package com.lunatech.tpcore.module.spawn;

import com.lunatech.tpcore.config.model.SpawnConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpawnConfigTest {

    @Test
    void testDefaultConfig() {
        SpawnConfig config = SpawnConfig.createDefault();
        assertTrue(config.enabled());
        assertEquals(3, config.warmupSeconds());
        assertEquals(10, config.cooldownSeconds());
        assertTrue(config.cancelOnMove());
        assertTrue(config.cancelOnDamage());
        assertTrue(config.spawnOnFirstJoin());
        assertFalse(config.spawnOnJoin());
        assertTrue(config.spawnOnRespawn());
        assertFalse(config.overrideBedRespawn());
        assertTrue(config.voidFallProtection());
        assertTrue(config.requireSafeLocation());
        assertFalse(config.economyEnabled());
        assertEquals(0.0, config.spawnCost(), 0.001);
        assertTrue(config.refundOnCancel());
        assertTrue(config.enableBossbar());
        assertEquals("YELLOW", config.bossbarColor());
        assertEquals("PROGRESS", config.bossbarOverlay());
        assertTrue(config.enableActionBar());
        assertFalse(config.enableTitle());
        assertTrue(config.enableSounds());
        assertEquals("minecraft:block.note_block.hat", config.tickSound());

        assertNotNull(config.messages());
        assertNotNull(config.messages().prefix());
        assertNotNull(config.messages().spawnTeleportSuccess());
        assertNotNull(config.messages().teleportFailed());
        assertNotNull(config.messages().warmupCancelledTeleport());
        assertNotNull(config.messages().mustBeInTargetWorld());
        assertNotNull(config.messages().costDeducted());
        assertNotNull(config.messages().costRefunded());
        assertNotNull(config.messages().insufficientFunds());
    }
}
