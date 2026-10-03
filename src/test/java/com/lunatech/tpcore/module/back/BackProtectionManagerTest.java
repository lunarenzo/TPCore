package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.service.impl.BackProtectionManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BackProtectionManagerTest {

    @Test
    @DisplayName("Protection lifecycle, start time, eviction, and clear work correctly")
    void testProtectionLifecycle() {
        BackConfig config = BackConfig.createDefault();
        BackProtectionManager manager = new BackProtectionManager(null, () -> config, MiniMessage.miniMessage());

        UUID playerId = UUID.randomUUID();
        assertFalse(manager.hasTeleportProtection(playerId));
        assertEquals(0L, manager.getTeleportProtectionStartTime(playerId));

        // Damage handling when no protection active
        assertFalse(manager.handlePlayerProtectionDamage(null, null, false));
        assertFalse(manager.handlePlayerProtectionDamage(null, null, true));

        // Evict & clear safety checks on empty manager
        manager.evict(playerId);
        manager.clear();
        assertFalse(manager.hasTeleportProtection(playerId));
    }
}
