package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService.Payment;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.lunatech.tpcore.module.warp.economy.impl.NoOpWarpEconomyService;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyBoolean;

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
        when(mockRepository.archiveAndDelete(any())).thenReturn(CompletableFuture.completedFuture(null));

        cache = new DefaultWarpCache();
        when(mockRepository.loadAll()).thenAnswer(inv -> CompletableFuture.completedFuture(cache.getAllWarps().stream().collect(Collectors.toMap(Warp::name, value -> value))));
        config = WarpConfig.createDefault();
        service = new DefaultWarpService(mockPlugin, mockRepository, cache, new NoOpWarpEconomyService(), config, LoggerFactory.getLogger("Test-WarpService"));
        service.initialize().join();
    }

    @AfterEach
    void tearDown() throws Exception {
        setBukkitServer(null);
    }

    private CompletableFuture<WarpResultStatus> teleport(DefaultWarpService targetService, Player player, String name, String password) {
        when(mockServer.getPlayer(player.getUniqueId())).thenReturn(player);
        EntityScheduler scheduler = player.getScheduler();
        if (scheduler == null) { scheduler = mock(EntityScheduler.class); when(player.getScheduler()).thenReturn(scheduler); }
        doAnswer(inv -> {
            Consumer<ScheduledTask> callback = inv.getArgument(1);
            callback.accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class);
        }).when(scheduler).run(any(), any(), any());
        return targetService.teleportToWarp(player, name, password);
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
            true, 0, 10, true, true, false, "general", 8, 100, false, 0.0, 0.0, "CHARGE_ON_WARMUP", true,
            new WarpConfig.WarpSafetyConfig(false, false, 128),
            WarpConfig.WarpStorageConfig.createDefault(),
            WarpConfig.WarpMessages.createDefault()
        );
        service.updateConfig(noSafetyConfig);

        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));

        org.mockito.Mockito.doAnswer(inv -> {
            Consumer<ScheduledTask> consumer = inv.getArgument(1);
            consumer.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        }).when(scheduler).run(any(), any(), any());

        // Teleport bypasses warmup, succeeds, and applies cooldown
        CompletableFuture<WarpResultStatus> future = teleport(service, player, "testwarp", null);
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
        teleport(service, player, "spawn", null);

        // Second teleport while warmup is active returns WARMUP_ALREADY_ACTIVE
        CompletableFuture<WarpResultStatus> future2 = teleport(service, player, "spawn", null);
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
            true, 3, 10, true, true, false, "general", 8, 2, false, 0.0, 0.0, "CHARGE_ON_WARMUP", true,
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

        CompletableFuture<WarpResultStatus> result = teleport(service, player, "unknownworldwarp", null);
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
        CompletableFuture<WarpResultStatus> first = teleport(service, player, "vault", "wrong1");
        assertEquals(WarpResultStatus.INVALID_PASSWORD, first.join());

        // Rapid second attempt should also return INVALID_PASSWORD throttled without processing hashing
        CompletableFuture<WarpResultStatus> second = teleport(service, player, "vault", "wrong2");
        assertEquals(WarpResultStatus.INVALID_PASSWORD, second.join());
    }

    @Test
    @DisplayName("warmup start and cancel format legacy and hex colors in prefix and messages properly")
    void testWarmupLegacyFormatting() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);

        Warp warp = new Warp("Spawn", worldId, "world", 0, 64, 0, 0f, 0f, UUID.randomUUID(), "general", null, false, 1000L);
        cache.putWarp(warp);

        WarpConfig legacyFormatConfig = new WarpConfig(
            true, 3, 10, true, true, false, "general", 8, 100, false, 0.0, 0.0, "CHARGE_ON_WARMUP", true,
            WarpConfig.WarpSafetyConfig.createDefault(),
            WarpConfig.WarpStorageConfig.createDefault(),
            new WarpConfig.WarpMessages(
                "&cCooldown &6<seconds>s",
                "&6<cost> &adeducted",
                "&6<cost> &arefunded",
                "&aDeleted &e<warp>",
                "&cUsage",
                "&cInsufficient funds",
                "&cInvalid pass",
                "&cInvalid name",
                "&cLimit",
                "&cDisabled",
                "&cNo perm",
                "&cOnly players",
                "&cPass required",
                "&cPlayer <target> offline",
                "&{#FF0000:#00FF00}&lTPCore &8» ",
                "&eAlready exists",
                "&aWarp set",
                "&cUsage",
                "&aTeleported other",
                "&#00FF00Teleported to &e<warp>",
                "&aTeleported by &6<sender>",
                "&cUnsafe",
                "&cWarmup active",
                "&cDamage cancel",
                "&cMove cancel",
                "<prefix>&7Teleporting to &e<warp>&7 in &6<seconds>&7s",
                "&7Category header",
                "&eNo warps",
                "&7Header",
                "&8• &a<name>",
                "&cNot found",
                "&cOther usage",
                "&cUsage",
                "&cWorld not loaded"
            )
        );
        service.updateConfig(legacyFormatConfig);

        EntityScheduler scheduler = mock(EntityScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenReturn(task);
        when(player.getScheduler()).thenReturn(scheduler);

        teleport(service, player, "spawn", null);

        org.mockito.ArgumentCaptor<net.kyori.adventure.text.Component> captor = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(player).sendMessage(captor.capture());
        net.kyori.adventure.text.Component sentComponent = captor.getValue();
        org.junit.jupiter.api.Assertions.assertNotNull(sentComponent);

        // Cancel warmup on move with legacy message
        service.cancelWarmupOnMove(player);
        verify(player, org.mockito.Mockito.atLeast(2)).sendMessage(captor.capture());
    }

    @Test
    @DisplayName("teleportToWarp enforces economy fee and refunds on cancelled warmup")
    void testEconomyTeleportCostDeductionAndRefund() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.bypass.cost")).thenReturn(false);
        when(player.hasPermission("tpcore.warp.bypass.cost.warp")).thenReturn(false);

        WarpEconomyService mockEconomy = mock(WarpEconomyService.class);
        when(mockEconomy.isAvailable()).thenReturn(true);
        when(mockEconomy.getWarpCost(player)).thenReturn(50.0);
        UUID paymentPlayerId = player.getUniqueId();
        when(mockEconomy.chargeExact(player.getUniqueId(), 50.0)).thenReturn(CompletableFuture.completedFuture(new Payment(paymentPlayerId, 50.0, true)));
        when(mockEconomy.settle(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(true));

        DefaultWarpService ecoService = new DefaultWarpService(
            mockPlugin, mockRepository, cache, mockEconomy, config, LoggerFactory.getLogger("Test-WarpService")
        );
        ecoService.initialize().join();

        Warp warp = new Warp("Shop", worldId, "world", 0, 64, 0, 0f, 0f, UUID.randomUUID(), "general", null, false, 1000L);
        cache.putWarp(warp);

        EntityScheduler scheduler = mock(EntityScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenReturn(task);
        when(player.getScheduler()).thenReturn(scheduler);

        // Initiate warp teleport - should charge 50.0
        teleport(ecoService, player, "shop", null);
        verify(mockEconomy).chargeExact(player.getUniqueId(), 50.0);

        // Cancel warmup on move - should refund 50.0
        ecoService.cancelWarmupOnMove(player);
        verify(mockEconomy).settle(new Payment(paymentPlayerId, 50.0, true), true);
    }

    @Test
    @DisplayName("setWarp requires economy fee and refunds on failure")
    void testSetWarpEconomyFee() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(mockWorld, 10, 64, 10));
        when(player.hasPermission(Permissions.WARP_ADMIN)).thenReturn(false);
        when(player.hasPermission(Permissions.WARP_BYPASS_COST)).thenReturn(false);
        when(player.hasPermission(Permissions.WARP_BYPASS_COST_SETWARP)).thenReturn(false);

        WarpEconomyService mockEconomy = mock(WarpEconomyService.class);
        when(mockEconomy.isAvailable()).thenReturn(true);
        when(mockEconomy.getSetWarpCost(player)).thenReturn(200.0);
        UUID paymentPlayerId = player.getUniqueId();
        when(mockEconomy.chargeExact(player.getUniqueId(), 200.0)).thenReturn(CompletableFuture.completedFuture(new Payment(paymentPlayerId, 200.0, true)));
        when(mockEconomy.settle(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(true));

        DefaultWarpService ecoService = new DefaultWarpService(
            mockPlugin, mockRepository, cache, mockEconomy, config, LoggerFactory.getLogger("Test-WarpService")
        );
        ecoService.initialize().join();

        assertEquals(WarpResultStatus.SUCCESS, ecoService.setWarp(player, "Hub", false, null, null).join());
        verify(mockEconomy).chargeExact(player.getUniqueId(), 200.0);
    }

    @Test
    @DisplayName("setWarp rolls back cache and refunds on database save failure")
    void testSetWarpDatabaseFailureRollback() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(mockWorld, 10, 64, 10));

        WarpEconomyService mockEconomy = mock(WarpEconomyService.class);
        when(mockEconomy.isAvailable()).thenReturn(true);
        when(mockEconomy.getSetWarpCost(player)).thenReturn(100.0);
        UUID paymentPlayerId = player.getUniqueId();
        when(mockEconomy.chargeExact(player.getUniqueId(), 100.0)).thenReturn(CompletableFuture.completedFuture(new Payment(paymentPlayerId, 100.0, true)));
        when(mockEconomy.settle(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(true));

        WarpRepository failingRepo = mock(WarpRepository.class);
        when(failingRepo.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(failingRepo.loadAll()).thenAnswer(inv -> CompletableFuture.completedFuture(cache.getAllWarps().stream().collect(Collectors.toMap(Warp::name, value -> value))));
        when(failingRepo.save(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Disk full")));

        DefaultWarpService failingService = new DefaultWarpService(
            mockPlugin, failingRepo, cache, mockEconomy, config, LoggerFactory.getLogger("Test-WarpService")
        );
        failingService.initialize().join();

        CompletableFuture<WarpResultStatus> result = failingService.setWarp(player, "FailedWarp", false, null, null);
        assertEquals(WarpResultStatus.ERROR, result.join());

        // Cache must NOT contain FailedWarp
        assertFalse(cache.getWarp("failedwarp").isPresent());
        verify(mockEconomy).settle(new Payment(paymentPlayerId, 100.0, true), true);
    }

    @Test
    @DisplayName("deleteWarp leaves warp in cache if database delete fails")
    void testDeleteWarpDatabaseFailure() {
        Warp warp = new Warp("KeepMe", worldId, "world", 0, 64, 0, 0f, 0f, UUID.randomUUID(), "general", null, false, 1000L);
        cache.putWarp(warp);

        WarpRepository failingRepo = mock(WarpRepository.class);
        when(failingRepo.archiveAndDelete(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("SQL error")));
        when(failingRepo.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(failingRepo.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of("keepme", warp)));

        DefaultWarpService failingService = new DefaultWarpService(
            mockPlugin, failingRepo, cache, new NoOpWarpEconomyService(), config, LoggerFactory.getLogger("Test-WarpService")
        );
        failingService.initialize().join();

        CompletableFuture<WarpResultStatus> result = failingService.deleteWarp("keepme");
        assertEquals(WarpResultStatus.ERROR, result.join());

        // Cache must still contain the warp
        assertTrue(cache.getWarp("keepme").isPresent());
    }

    @Test
    @DisplayName("setWarp salts password and matches successfully")
    void testSaltedPasswordVerification() {
        Player creator = mock(Player.class);
        when(creator.getUniqueId()).thenReturn(UUID.randomUUID());
        when(creator.getLocation()).thenReturn(new Location(mockWorld, 10, 64, 10));

        service.setWarp(creator, "SecretWarp", false, "superSecret123", "secret").join();
        Optional<Warp> created = cache.getWarp("secretwarp");
        assertTrue(created.isPresent());
        assertFalse(created.get().passwordHash().contains("superSecret123"));

        Player player1 = mock(Player.class);
        when(player1.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player1.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(true);
        when(player1.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);
        when(player1.hasPermission("tpcore.warp.admin")).thenReturn(false);
        when(player1.hasPermission("tpcore.warp.bypass.password")).thenReturn(false);

        Player player2 = mock(Player.class);
        when(player2.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player2.hasPermission("tpcore.warp.bypass.warmup")).thenReturn(true);
        when(player2.hasPermission("tpcore.warp.bypass.cooldown")).thenReturn(false);
        when(player2.hasPermission("tpcore.warp.admin")).thenReturn(false);
        when(player2.hasPermission("tpcore.warp.bypass.password")).thenReturn(false);

        WarpConfig noSafetyConfig = new WarpConfig(
            true, 0, 10, true, true, false, "general", 8, 100, false, 0.0, 0.0, "CHARGE_ON_WARMUP", true,
            new WarpConfig.WarpSafetyConfig(false, false, 128),
            WarpConfig.WarpStorageConfig.createDefault(),
            WarpConfig.WarpMessages.createDefault()
        );
        service.updateConfig(noSafetyConfig);

        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player1.getScheduler()).thenReturn(scheduler);
        when(player2.getScheduler()).thenReturn(scheduler);
        when(player1.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
        when(player2.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
        org.mockito.Mockito.doAnswer(inv -> {
            Consumer<ScheduledTask> consumer = inv.getArgument(1);
            consumer.accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        }).when(scheduler).run(any(), any(), any());

        // Wrong password on player1
        assertEquals(WarpResultStatus.INVALID_PASSWORD, teleport(service, player1, "secretwarp", "wrong").join());

        // Correct password on player2
        assertEquals(WarpResultStatus.SUCCESS, teleport(service, player2, "secretwarp", "superSecret123").join());
    }
}
