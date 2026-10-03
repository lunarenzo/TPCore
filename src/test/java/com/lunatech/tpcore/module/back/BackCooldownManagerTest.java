package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.module.back.service.impl.BackCooldownManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackCooldownManagerTest {

    private BackCooldownManager cooldownManager;
    private final UUID playerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cooldownManager = new BackCooldownManager();
    }

    @Test
    @DisplayName("Cooldown is initially inactive")
    void testInitialCooldown() {
        assertFalse(cooldownManager.isOnCooldown(playerUuid, 10));
        assertEquals(0, cooldownManager.getRemainingCooldownSeconds(playerUuid, 10));
    }

    @Test
    @DisplayName("applyCooldown activates cooldown and reports remaining seconds")
    void testApplyCooldown() {
        cooldownManager.applyCooldown(playerUuid);

        assertTrue(cooldownManager.isOnCooldown(playerUuid, 10));
        long remaining = cooldownManager.getRemainingCooldownSeconds(playerUuid, 10);
        assertTrue(remaining >= 9 && remaining <= 10, "Remaining seconds should be between 9 and 10");

        cooldownManager.removeCooldown(playerUuid);
        assertFalse(cooldownManager.isOnCooldown(playerUuid, 10));
    }
}
