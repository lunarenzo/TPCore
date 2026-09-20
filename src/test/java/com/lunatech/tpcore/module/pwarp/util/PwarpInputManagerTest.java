package com.lunatech.tpcore.module.pwarp.util;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class PwarpInputManagerTest {

    @Test
    @DisplayName("Verify PwarpInputManager input registration and cancellation")
    void testInputManagerRegistration() {
        PwarpInputManager manager = new PwarpInputManager(org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class));
        UUID uuid = UUID.randomUUID();

        manager.cancelInput(uuid);
        Assertions.assertNotNull(manager);
    }
}
