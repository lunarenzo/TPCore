package com.lunatech.tpcore.module.spawn;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.impl.SpawnProtectionManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpawnProtectionManagerTest {

    @Test
    void testProtectionLifecycle() {
        SpawnConfig config = SpawnConfig.createDefault();
        SpawnProtectionManager manager = new SpawnProtectionManager(null, () -> config, MiniMessage.miniMessage());

        UUID playerId = UUID.randomUUID();
        assertFalse(manager.hasTeleportProtection(playerId));
        assertEquals(0L, manager.getTeleportProtectionStartTime(playerId));

        // Evict & clear safety checks on empty manager
        manager.evict(playerId);
        manager.clear();
        assertFalse(manager.hasTeleportProtection(playerId));
    }
}
