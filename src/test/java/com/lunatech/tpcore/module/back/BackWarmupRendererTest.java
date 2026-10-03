package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.service.impl.BackWarmupManager;
import com.lunatech.tpcore.module.back.service.impl.BackWarmupRenderer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BackWarmupRendererTest {

    @Test
    @DisplayName("BossBar is created and updated according to BackConfig")
    void testBossBarCreationAndUpdates() {
        BackConfig config = BackConfig.createDefault();
        BackWarmupRenderer renderer = new BackWarmupRenderer(MiniMessage.miniMessage());

        BossBar bossBar = renderer.createBossBar(config, 5);
        assertNotNull(bossBar);
        assertEquals(1.0f, bossBar.progress());

        renderer.updateBossBar(bossBar, config, 3, 5);
        assertEquals(0.6f, bossBar.progress(), 0.001f);

        renderer.clear();
    }

    @Test
    @DisplayName("BackWarmupManager lifecycle functions safely without active player sessions")
    void testWarmupManagerLifecycle() {
        BackConfig config = BackConfig.createDefault();
        BackWarmupRenderer renderer = new BackWarmupRenderer(MiniMessage.miniMessage());
        BackWarmupManager warmupManager = new BackWarmupManager(null, () -> config, renderer, MiniMessage.miniMessage());

        UUID uuid = UUID.randomUUID();
        assertFalse(warmupManager.hasActiveWarmup(uuid));

        warmupManager.cancelWarmup(uuid);
        warmupManager.handlePlayerQuit(uuid);
        warmupManager.clear();
        assertFalse(warmupManager.hasActiveWarmup(uuid));
    }
}
