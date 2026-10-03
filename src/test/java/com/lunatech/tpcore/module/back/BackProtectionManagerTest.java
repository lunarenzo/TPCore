package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.module.back.service.impl.BackProtectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackProtectionManagerTest {

    private BackProtectionManager protectionManager;
    private final UUID playerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        protectionManager = new BackProtectionManager();
    }

    @Test
    @DisplayName("grantProtection protects player and removeProtection clears it")
    void testProtectionLifecycle() {
        assertFalse(protectionManager.isProtected(playerUuid));

        protectionManager.grantProtection(playerUuid, 5);
        assertTrue(protectionManager.isProtected(playerUuid));

        protectionManager.removeProtection(playerUuid);
        assertFalse(protectionManager.isProtected(playerUuid));
    }
}
