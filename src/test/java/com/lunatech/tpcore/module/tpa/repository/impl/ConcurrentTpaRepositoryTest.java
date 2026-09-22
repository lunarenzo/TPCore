package com.lunatech.tpcore.module.tpa.repository.impl;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lunatech.tpcore.module.tpa.model.TpaUserSettings;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentTpaRepositoryTest {

    private ConcurrentTpaRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new ConcurrentTpaRepository();
    }

    @Test
    @DisplayName("removeAllRequestsForPlayer evicts toggledOff status and request mappings")
    void testRemoveAllRequestsForPlayerEvictsToggledOff() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        TpaRequest req = new TpaRequest(player1, player2, TpaType.TPA_TO, System.currentTimeMillis());
        this.repository.addRequest(req);

        this.repository.setTpaToggledOff(player1, true);
        this.repository.removeAllRequestsForPlayer(player1);

        assertFalse(this.repository.isTpaToggledOff(player1));
        assertTrue(this.repository.getOutgoingRequests(player1).isEmpty());
        assertTrue(this.repository.getIncomingRequests(player2).isEmpty());
    }

    @Test
    @DisplayName("UserSettings and player blocking operations function correctly")
    void testUserSettingsAndBlocking() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        // Default setting should not be toggled off and empty blocked set
        TpaUserSettings defaultSettings = this.repository.getUserSettings(player1);
        assertFalse(defaultSettings.toggledOff());
        assertTrue(defaultSettings.blockedPlayers().isEmpty());

        // Block player2
        this.repository.setPlayerBlocked(player1, player2, true);
        assertTrue(this.repository.isPlayerBlocked(player1, player2));
        assertTrue(this.repository.getUserSettings(player1).blockedPlayers().contains(player2));

        // Unblock player2
        this.repository.setPlayerBlocked(player1, player2, false);
        assertFalse(this.repository.isPlayerBlocked(player1, player2));
        assertFalse(this.repository.getUserSettings(player1).blockedPlayers().contains(player2));

        // Set user settings directly
        TpaUserSettings newSettings = new TpaUserSettings(true, true, Set.of(player2));
        this.repository.setUserSettings(player1, newSettings);
        assertTrue(this.repository.isTpaToggledOff(player1));
        assertTrue(this.repository.isAutoAcceptEnabled(player1));
        assertTrue(this.repository.isPlayerBlocked(player1, player2));

        // Clean up on disconnect
        this.repository.removeAllRequestsForPlayer(player1);
        assertFalse(this.repository.isTpaToggledOff(player1));
        assertFalse(this.repository.isAutoAcceptEnabled(player1));
        assertFalse(this.repository.isPlayerBlocked(player1, player2));
    }

    @Test
    @DisplayName("Cooldown tracking functions correctly and cleans up on quit")
    void testCooldownTracking() {
        UUID player1 = UUID.randomUUID();
        long futureTime = System.currentTimeMillis() + 10000L;

        assertEquals(0L, this.repository.getCooldownEnd(player1));
        this.repository.setCooldownEnd(player1, futureTime);
        assertEquals(futureTime, this.repository.getCooldownEnd(player1));

        this.repository.removeAllRequestsForPlayer(player1);
        assertEquals(0L, this.repository.getCooldownEnd(player1));
    }
}
