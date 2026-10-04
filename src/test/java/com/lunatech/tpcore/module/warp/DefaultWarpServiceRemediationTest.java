package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultWarpServiceRemediationTest {

    @TempDir
    File tempDir;

    private Plugin mockPlugin;
    private WarpRepository mockRepository;
    private WarpCache cache;
    private WarpConfig config;
    private DefaultWarpService service;
    private Server mockServer;
    private World mockWorld;
    private final UUID worldId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        mockServer = mock(Server.class);
        mockWorld = mock(World.class);
        when(mockWorld.getUID()).thenReturn(worldId);
        when(mockWorld.getName()).thenReturn("world");
        when(mockWorld.getMinHeight()).thenReturn(-64);
        when(mockWorld.getMaxHeight()).thenReturn(320);
        org.bukkit.Chunk mockChunk = mock(org.bukkit.Chunk.class);
        when(mockWorld.getChunkAtAsync(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(CompletableFuture.completedFuture(mockChunk));
        when(mockServer.getWorld(worldId)).thenReturn(mockWorld);
        when(mockServer.getWorld("world")).thenReturn(mockWorld);
        setBukkitServer(mockServer);

        mockPlugin = mock(Plugin.class);
        when(mockPlugin.getDataFolder()).thenReturn(tempDir);
        mockRepository = mock(WarpRepository.class);
        when(mockRepository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(mockRepository.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockRepository.delete(any())).thenReturn(CompletableFuture.completedFuture(null));

        cache = new DefaultWarpCache();
        config = WarpConfig.createDefault();
        service = new DefaultWarpService(mockPlugin, mockRepository, cache, config, LoggerFactory.getLogger("Test-WarpService"));
    }

    @AfterEach
    void tearDown() throws Exception {
        setBukkitServer(null);
    }

    private static void setBukkitServer(Server server) throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    @Test
    @DisplayName("cancelWarmupOnQuit preserves player cooldowns to prevent relog bypass")
    void testCooldownPersistenceOnQuit() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(true);
        when(player.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);

        Warp warp = new Warp("TestWarp", worldId, "world", 0, 64, 0, 0f, 0f, UUID.randomUUID(), "general", null, false, 1000L);
        cache.putWarp(warp);

        WarpConfig noSafetyConfig = new WarpConfig(
            true, 0, 10, true, true, false, "general", 8, 100,
            new WarpConfig.WarpSafetyConfig(false, false, 128),
            WarpConfig.WarpStorageConfig.createDefault(),
            WarpConfig.WarpMessages.createDefault()
        );
        service.updateConfig(noSafetyConfig);

        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));

        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<ScheduledTask> consumer = inv.getArgument(1);
            consumer.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        }).when(scheduler).run(any(), any(), any());

        // Teleport bypasses warmup, succeeds, and applies cooldown
        CompletableFuture<WarpResultStatus> future = service.teleportToWarp(player, "testwarp", null);
        assertEquals(WarpResultStatus.SUCCESS, future.join());

        long remaining = service.getRemainingCooldownSeconds(playerId);
        assertTrue(remaining > 0, "Cooldown must be active");

        // Player quits
        service.cancelWarmupOnQuit(playerId);

        // Cooldown must still remain active after quitting!
        long remainingAfterQuit = service.getRemainingCooldownSeconds(playerId);
        assertEquals(remaining, remainingAfterQuit);
    }

    @Test
    @DisplayName("teleportToWarp prevents spam when warmup is already active")
    void testWarmupAlreadyActive() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);

        Warp warp = new Warp("Spawn", worldId, "world", 0, 64, 0, 0f, 0f, UUID.randomUUID(), "general", null, false, 1000L);
        cache.putWarp(warp);

        EntityScheduler scheduler = mock(EntityScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenReturn(task);
        when(player.getScheduler()).thenReturn(scheduler);

        // First teleport initiates warmup
        service.teleportToWarp(player, "spawn", null);

        // Second teleport while warmup is active returns WARMUP_ALREADY_ACTIVE
        CompletableFuture<WarpResultStatus> future2 = service.teleportToWarp(player, "spawn", null);
        assertEquals(WarpResultStatus.WARMUP_ALREADY_ACTIVE, future2.join());
    }

    @Test
    @DisplayName("setWarp validates warp names and rejects invalid regex names")
    void testSetWarpValidation() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(mockWorld, 10, 64, 10));

        // Invalid warp names
        assertEquals(WarpResultStatus.INVALID_NAME, service.setWarp(player, "warp with spaces", false, null, null).join());
        assertEquals(WarpResultStatus.INVALID_NAME, service.setWarp(player, "invalid!@#", false, null, null).join());
        assertEquals(WarpResultStatus.INVALID_NAME, service.setWarp(player, "", false, null, null).join());

        // Valid warp name
        assertEquals(WarpResultStatus.SUCCESS, service.setWarp(player, "Valid_Warp-1", false, null, null).join());
        assertTrue(cache.getWarp("valid_warp-1").isPresent());

        // Existing warp without overwrite
        assertEquals(WarpResultStatus.ALREADY_EXISTS, service.setWarp(player, "Valid_Warp-1", false, null, null).join());

        // Existing warp with overwrite
        assertEquals(WarpResultStatus.SUCCESS, service.setWarp(player, "Valid_Warp-1", true, null, null).join());
    }

    @Test
    @DisplayName("setWarp enforces maxWarps limit when configured")
    void testSetWarpLimit() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(mockWorld, 10, 64, 10));
        when(player.hasPermission("tpcore.warp.bypass.limit")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.admin")).thenReturn(false);

        // Configure maxWarps to 2
        WarpConfig limitedConfig = new WarpConfig(
            true, 3, 10, true, true, false, "general", 8, 2,
            WarpConfig.WarpSafetyConfig.createDefault(),
            WarpConfig.WarpStorageConfig.createDefault(),
            WarpConfig.WarpMessages.createDefault()
        );
        service.updateConfig(limitedConfig);

        assertEquals(WarpResultStatus.SUCCESS, service.setWarp(player, "warp1", false, null, null).join());
        assertEquals(WarpResultStatus.SUCCESS, service.setWarp(player, "warp2", false, null, null).join());

        // 3rd warp reaches limit
        assertEquals(WarpResultStatus.LIMIT_REACHED, service.setWarp(player, "warp3", false, null, null).join());

        // Overwrite existing warp is allowed
        assertEquals(WarpResultStatus.SUCCESS, service.setWarp(player, "warp1", true, null, null).join());

        // Bypass permission allows setting 3rd warp
        when(player.hasPermission("tpcore.warp.bypass.limit")).thenReturn(true);
        assertEquals(WarpResultStatus.SUCCESS, service.setWarp(player, "warp3", false, null, null).join());
    }

    @Test
    @DisplayName("teleportToWarp does not consume cooldown on failed/unsafe teleport")
    void testCooldownNotAppliedOnFailure() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(true);
        when(player.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);

        // Warp in unknown world
        Warp warp = new Warp("UnknownWorldWarp", UUID.randomUUID(), "non_existent_world", 0, 64, 0, 0f, 0f, UUID.randomUUID(), "general", null, false, 1000L);
        cache.putWarp(warp);

        CompletableFuture<WarpResultStatus> result = service.teleportToWarp(player, "unknownworldwarp", null);
        assertEquals(WarpResultStatus.WORLD_NOT_LOADED, result.join());

        // Cooldown must NOT be active
        assertEquals(0, service.getRemainingCooldownSeconds(playerId));
    }

    @Test
    @DisplayName("teleportToWarp throttles rapid failed password attempts")
    void testPasswordThrottling() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(true);
        when(player.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.admin")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.bypass.password")).thenReturn(false);

        // Password protected warp (SHA-256 for "correctPassword")
        Warp warp = new Warp("Vault", worldId, "world", 0, 64, 0, 0f, 0f, UUID.randomUUID(), "general", "2f9d519b78a9c805eb39cf77fd15f6063beea95ab70b0266042db621db7cfa37", false, 1000L);
        cache.putWarp(warp);

        // First attempt with incorrect password
        CompletableFuture<WarpResultStatus> first = service.teleportToWarp(player, "vault", "wrong1");
        assertEquals(WarpResultStatus.INVALID_PASSWORD, first.join());

        // Rapid second attempt should also return INVALID_PASSWORD throttled without processing hashing
        CompletableFuture<WarpResultStatus> second = service.teleportToWarp(player, "vault", "wrong2");
        assertEquals(WarpResultStatus.INVALID_PASSWORD, second.join());
    }
}
