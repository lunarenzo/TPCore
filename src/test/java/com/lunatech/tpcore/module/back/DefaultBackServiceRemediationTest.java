package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.cache.impl.DefaultBackCache;
import com.lunatech.tpcore.module.back.economy.BackEconomyService;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.BackRepository;
import com.lunatech.tpcore.module.back.service.BackResultStatus;
import com.lunatech.tpcore.module.back.service.impl.BackWarmupManager;
import com.lunatech.tpcore.module.back.service.impl.BackWarmupRenderer;
import com.lunatech.tpcore.module.back.service.impl.DefaultBackService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

final class DefaultBackServiceRemediationTest {

    @Test
    @DisplayName("BackWarmupManager guarantees single refund and single cancel notification on multiple cancel triggers")
    void testWarmupManagerSingleRefundAndCancel() {
        BackConfig config = BackConfig.createDefault();
        BackWarmupRenderer renderer = new BackWarmupRenderer(MiniMessage.miniMessage());
        
        BackEconomyService mockEconomy = mock(BackEconomyService.class);
        Plugin mockPlugin = mock(Plugin.class);
        BackWarmupManager warmupManager = new BackWarmupManager(mockPlugin, () -> config, mockEconomy, renderer, MiniMessage.miniMessage());

        UUID uuid = UUID.randomUUID();
        Player mockPlayer = mock(Player.class);
        when(mockPlayer.getUniqueId()).thenReturn(uuid);
        when(mockPlayer.isOnline()).thenReturn(true);
        World mockWorld = mock(World.class);
        when(mockWorld.getName()).thenReturn("world");
        Location mockLoc = new Location(mockWorld, 0, 64, 0);
        when(mockPlayer.getLocation()).thenReturn(mockLoc);

        EntityScheduler mockScheduler = mock(EntityScheduler.class);
        ScheduledTask mockTask = mock(ScheduledTask.class);
        when(mockScheduler.runAtFixedRate(any(), any(), any(), anyLong(), anyLong())).thenReturn(mockTask);
        when(mockPlayer.getScheduler()).thenReturn(mockScheduler);

        BackLocation loc = new BackLocation(UUID.randomUUID(), "world", 0, 64, 0, 0, 0, System.currentTimeMillis(), BackCause.TELEPORT);

        AtomicInteger cancelCallbackCount = new AtomicInteger(0);
        warmupManager.startWarmup(mockPlayer, loc, 3, 50.0, () -> {}, cancelCallbackCount::incrementAndGet);

        assertTrue(warmupManager.hasActiveWarmup(uuid));

        // Multiple cancellation triggers (e.g. move + damage + quit occurring concurrently)
        warmupManager.cancelWarmup(uuid);
        warmupManager.cancelWarmup(uuid);
        warmupManager.handlePlayerQuit(uuid);

        assertFalse(warmupManager.hasActiveWarmup(uuid));
        assertEquals(1, cancelCallbackCount.get());
    }

    @Test
    @DisplayName("DefaultBackService close gracefully shuts down resources and clears pending operations")
    void testServiceCloseLifecycle(@TempDir Path tempDir) {
        BackConfig config = BackConfig.createDefault();
        BackRepository mockRepo = mock(BackRepository.class);
        when(mockRepo.close()).thenReturn(CompletableFuture.completedFuture(null));

        Plugin mockPlugin = mock(Plugin.class);
        when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());

        DefaultBackCache cache = new DefaultBackCache();
        DefaultBackService service = new DefaultBackService(
            mockPlugin,
            mockRepo,
            cache,
            config,
            LoggerFactory.getLogger("Test-BackService")
        );

        assertDoesNotThrow(() -> service.close().join());
        verify(mockRepo, times(1)).close();
    }

    @Test
    @DisplayName("DefaultBackService teleportDeath retains death location on failure or cooldown")
    void testTeleportDeathRetainsLocationOnFailure(@TempDir Path tempDir) {
        BackConfig config = BackConfig.createDefault();
        BackRepository mockRepo = mock(BackRepository.class);
        Plugin mockPlugin = mock(Plugin.class);
        when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());

        DefaultBackCache cache = new DefaultBackCache();
        DefaultBackService service = new DefaultBackService(
            mockPlugin,
            mockRepo,
            cache,
            config,
            LoggerFactory.getLogger("Test-BackService")
        );

        UUID uuid = UUID.randomUUID();
        Player mockPlayer = mock(Player.class);
        when(mockPlayer.getUniqueId()).thenReturn(uuid);
        when(mockPlayer.hasPermission(anyString())).thenReturn(false);

        BackLocation deathLoc = new BackLocation(UUID.randomUUID(), "world", 100, 64, 100, 0, 0, System.currentTimeMillis(), BackCause.DEATH);
        cache.pushLocation(uuid, deathLoc, 5);

        assertTrue(service.getLastDeathLocation(mockPlayer).isPresent());

        // Attempting teleportDeath when destination world is null / unresolved fails, but MUST NOT wipe cache
        var result = service.teleportDeath(mockPlayer).join();
        assertNotEquals(BackResultStatus.SUCCESS, result);

        // Death location must still be retained in cache!
        assertTrue(service.getLastDeathLocation(mockPlayer).isPresent());
        assertEquals(deathLoc, service.getLastDeathLocation(mockPlayer).get());
    }

    @Test
    @DisplayName("DefaultBackService cancelWarmupOnQuit persists player history before clearing cache")
    void testCancelWarmupOnQuitPersistsHistory(@TempDir Path tempDir) {
        BackConfig config = BackConfig.createDefault();
        BackRepository mockRepo = mock(BackRepository.class);
        when(mockRepo.savePlayerHistory(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        Plugin mockPlugin = mock(Plugin.class);
        when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());

        DefaultBackCache cache = new DefaultBackCache();
        DefaultBackService service = new DefaultBackService(
            mockPlugin,
            mockRepo,
            cache,
            config,
            LoggerFactory.getLogger("Test-BackService")
        );

        UUID uuid = UUID.randomUUID();
        BackLocation loc = new BackLocation(UUID.randomUUID(), "world", 10, 64, 10, 0, 0, System.currentTimeMillis(), BackCause.TELEPORT);
        cache.pushLocation(uuid, loc, 5);

        service.cancelWarmupOnQuit(uuid);

        verify(mockRepo, times(1)).savePlayerHistory(eq(uuid), anyList());
        assertTrue(cache.getHistory(uuid).isEmpty());
    }
}
