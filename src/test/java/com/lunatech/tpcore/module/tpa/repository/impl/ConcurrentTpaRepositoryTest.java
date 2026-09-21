package com.lunatech.tpcore.module.tpa.repository.impl;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        assertTrue(this.repository.isTpaToggledOff(player1));

        this.repository.removeAllRequestsForPlayer(player1);

        assertFalse(this.repository.isTpaToggledOff(player1));
        assertTrue(this.repository.getOutgoingRequests(player1).isEmpty());
        assertTrue(this.repository.getIncomingRequests(player2).isEmpty());
    }
}
