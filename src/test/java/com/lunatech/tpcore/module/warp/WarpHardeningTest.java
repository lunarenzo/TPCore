package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpConfig.WarpSafetyConfig;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService.Payment;
import com.lunatech.tpcore.module.warp.listener.WarpEventListener;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpTeleportTracker;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpHardeningTest {
    @TempDir Path directory;
    private WarpConfig settings(boolean safe, String timing) {
        WarpConfig d = WarpConfig.createDefault();
        return new WarpConfig(true, 0, 0, true, true, false, "general", 8, 100, true, 12, 0, timing, true,
            new WarpSafetyConfig(safe, false, 128, "EXACT", 0, 1, "DENY", Set.of("LAVA")), d.storage(), d.messages(),
            1, 100, 100, 16, 1, d.maxStorageBytes(), d.editMessages(), d.control(), d.controlMessages(), d.browsing(),
            d.essentialsImport(), d.policies(), d.signs(), d.payments());
    }
    private final class Fixture implements AutoCloseable {
        final UUID id = UUID.randomUUID(), worldId = UUID.randomUUID();
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final Server server = mock(Server.class);
        final Plugin plugin = mock(Plugin.class);
        final EntityScheduler scheduler = mock(EntityScheduler.class);
        final RegionScheduler regions = mock(RegionScheduler.class);
        final WarpRepository repository = mock(WarpRepository.class);
        final WarpEconomyService economy = mock(WarpEconomyService.class);
        final DefaultWarpCache cache = new DefaultWarpCache();
        final WarpTeleportTracker tracker = new WarpTeleportTracker(2);
        final DefaultWarpService service;
        final Field serverField;
        final Object previousServer;
        Fixture(boolean safe, String timing) throws Exception {
            serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
            previousServer = serverField.get(null); serverField.set(null, server);
            when(server.getWorld(worldId)).thenReturn(world); when(server.getWorld("world")).thenReturn(world);
            when(server.getPlayer(id)).thenReturn(player); when(server.getRegionScheduler()).thenReturn(regions);
            when(world.getUID()).thenReturn(worldId); when(world.getName()).thenReturn("world");
            when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(world.getChunkAtAsync(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
            Block air = mock(Block.class), ground = mock(Block.class);
            when(air.getType()).thenReturn(Material.AIR); when(air.isPassable()).thenReturn(true);
            when(ground.getType()).thenReturn(Material.BEDROCK);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(1) < 64 ? ground : air);
            doAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return null; }).when(regions).execute(any(), any(Location.class), any(Runnable.class));
            when(player.getUniqueId()).thenReturn(id); when(player.getLocation()).thenReturn(new Location(world, .5, 64, .5));
            when(player.getScheduler()).thenReturn(scheduler); when(player.hasPermission("tpcore.warp.use")).thenReturn(true);
            when(player.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
            doAnswer(call -> { Consumer<ScheduledTask> action = call.getArgument(1); action.accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class); }).when(scheduler).run(any(), any(), any());
            when(plugin.getDataFolder()).thenReturn(directory.toFile());
            when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(repository.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of()));
            when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
            when(economy.isAvailable()).thenReturn(true); when(economy.format(anyDouble())).thenReturn("12.00");
            when(economy.validateExact(any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(true));
            when(economy.chargeExact(any(), anyDouble(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(new Payment(id, 12, true, UUID.randomUUID())));
            when(economy.settle(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(true));
            when(economy.close()).thenReturn(CompletableFuture.completedFuture(null));
            service = new DefaultWarpService(plugin, repository, cache, economy, settings(safe, timing), LoggerFactory.getLogger("WarpHardening"), tracker);
            service.initialize().join(); cache.putWarp(warp(worldId));
        }
        Warp warp(UUID world) { return new Warp("hub", world, "world", .5, 64, .5, 0, 0, id, "general", null, false, 123); }
        @Override public void close() throws Exception {
            try { service.close().get(3, TimeUnit.SECONDS); } finally { serverField.set(null, previousServer); }
        }
    }

    private void awaitCapacity(DefaultWarpService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (service.diagnostics().pendingOperations() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, service.diagnostics().pendingOperations());
    }
    @Test void stalledChunkObservationReleasesOrdinaryCapacityAndNeverTeleportsLate() throws Exception {
        try (var f = new Fixture(true, "CHARGE_ON_WARMUP")) {
            CompletableFuture<Chunk> chunk = new CompletableFuture<>(); when(f.world.getChunkAtAsync(anyInt(), anyInt())).thenReturn(chunk);
            assertEquals(WarpResultStatus.ERROR, f.service.teleportToWarp(f.player, "hub", null).get(3, TimeUnit.SECONDS));
            awaitCapacity(f.service); assertEquals(0, f.service.diagnostics().activeTeleports());
            chunk.complete(mock(Chunk.class)); verify(f.player, never()).teleportAsync(any());
            verify(f.economy).settle(any(), eq(true));
        }
    }
    @Test void stalledDispatchedTeleportReleasesCapacityWithoutRefundAndBlocksRetryUntilObserved() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            CompletableFuture<Boolean> nativeTeleport = new CompletableFuture<>(); when(f.player.teleportAsync(any())).thenReturn(nativeTeleport);
            assertEquals(WarpResultStatus.TELEPORT_PENDING, f.service.teleportToWarp(f.player, "hub", null).get(3, TimeUnit.SECONDS));
            awaitCapacity(f.service); assertEquals(1, f.tracker.size());
            assertEquals(WarpResultStatus.TELEPORT_PENDING, f.service.teleportToWarp(f.player, "hub", null).join());
            verify(f.economy).retainUncertain(any(), contains("outcome unknown")); verify(f.economy, never()).settle(any(), anyBoolean());
            when(f.repository.save(any())).thenReturn(CompletableFuture.completedFuture(null));
            when(f.economy.chargeExact(eq(f.id), eq(0.0), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(new Payment(f.id, 0, true)));
            assertEquals(WarpResultStatus.SUCCESS, f.service.setWarp(f.player, "free", false, null, null).join());
            nativeTeleport.complete(true); assertEquals(0, f.tracker.size());
            verify(f.economy, never()).settle(any(), eq(true));
            when(f.player.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
            assertEquals(WarpResultStatus.SUCCESS, f.service.teleportToWarp(f.player, "hub", null).join());
        }
    }
    @Test void fullDispatchQuarantineRefundsOperationalRejectionEvenWhenCancellationRefundsAreDisabled() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            WarpConfig config = spy(settings(false, "CHARGE_ON_WARMUP")); when(config.refundOnCancel()).thenReturn(false);
            f.service.updateConfig(config);
            for (int index = 0; index < 2; index++) assertTrue(f.tracker.reserve(new WarpTeleportTracker.Evidence(
                UUID.randomUUID(), UUID.randomUUID(), "other", f.worldId, 0, 64, 0, null, System.currentTimeMillis())));
            assertEquals(WarpResultStatus.BUSY, f.service.teleportToWarp(f.player, "hub", null).join());
            verify(f.economy).settle(any(), eq(true)); verify(f.player, never()).teleportAsync(any());
        }
    }
    @Test void closeDetachesLiveTeleportAndKeepsItsEvidenceUntilNativeObservation() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            CompletableFuture<Boolean> nativeTeleport = new CompletableFuture<>(); when(f.player.teleportAsync(any())).thenReturn(nativeTeleport);
            var result = f.service.teleportToWarp(f.player, "hub", null);
            f.service.close().get(2, TimeUnit.SECONDS);
            assertEquals(WarpResultStatus.TELEPORT_PENDING, result.join()); assertEquals(1, f.tracker.size());
            assertEquals(0, f.service.diagnostics().pendingOperations());
            nativeTeleport.complete(false); assertEquals(0, f.tracker.size());
            verify(f.economy, never()).settle(any(), anyBoolean());
        }
    }
    @Test void unsafeChangeDuringWithdrawalIsRejectedAndRefundsReceipt() throws Exception {
        try (var f = new Fixture(true, "CHARGE_ON_SUCCESS")) {
            CompletableFuture<Payment> funding = new CompletableFuture<>(); when(f.economy.chargeExact(any(), anyDouble(), anyString(), anyString())).thenReturn(funding);
            var result = f.service.teleportToWarp(f.player, "hub", null);
            Block hazard = mock(Block.class); when(hazard.getType()).thenReturn(Material.LAVA);
            when(f.world.getBlockAt(0, 63, 0)).thenReturn(hazard);
            Payment receipt = new Payment(f.id, 12, true); funding.complete(receipt);
            assertEquals(WarpResultStatus.UNSAFE_LOCATION, result.join()); verify(f.player, never()).teleportAsync(any());
            verify(f.economy).settle(receipt, true);
        }
    }
    @Test void staleFinalInspectionFailsClosedInsteadOfDispatching() throws Exception {
        try (var f = new Fixture(true, "CHARGE_ON_WARMUP")) {
            var queued = new ArrayList<Consumer<ScheduledTask>>();
            doAnswer(call -> { queued.add(call.getArgument(1)); return mock(ScheduledTask.class); }).when(f.scheduler).run(any(), any(), any());
            var result = f.service.teleportToWarp(f.player, "hub", null);
            queued.getFirst().accept(mock(ScheduledTask.class));
            assertEquals(2, queued.size()); Thread.sleep(300);
            queued.getLast().accept(mock(ScheduledTask.class));
            assertEquals(WarpResultStatus.UNSAFE_LOCATION, result.join()); verify(f.player, never()).teleportAsync(any());
        }
    }
    @Test void dispatchTrackerIsBoundedAndSharedAcrossServiceLifetimes() {
        var tracker = new WarpTeleportTracker(1);
        var evidence = new WarpTeleportTracker.Evidence(UUID.randomUUID(), UUID.randomUUID(), "hub", UUID.randomUUID(), 0, 64, 0, null, System.currentTimeMillis());
        assertTrue(tracker.reserve(evidence)); assertTrue(tracker.contains(evidence.player()));
        assertFalse(tracker.reserve(new WarpTeleportTracker.Evidence(UUID.randomUUID(), UUID.randomUUID(), "other", evidence.world(), 0, 64, 0, null, 0)));
        assertEquals(1, tracker.size()); tracker.complete(evidence); tracker.complete(evidence); assertEquals(0, tracker.size());
        assertTrue(tracker.reserve(evidence));
    }
    @Test void revokedBasePermissionStopsDepartureAfterFunding() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            CompletableFuture<Payment> funding = new CompletableFuture<>(); when(f.economy.chargeExact(any(), anyDouble(), anyString(), anyString())).thenReturn(funding);
            var result = f.service.teleportToWarp(f.player, "hub", null);
            when(f.player.hasPermission("tpcore.warp.use")).thenReturn(false);
            funding.complete(new Payment(f.id, 12, true));
            assertEquals(WarpResultStatus.NO_PERMISSION, result.join()); verify(f.player, never()).teleportAsync(any());
        }
    }
    @Test void savedUuidNeverFallsBackToReplacementWorldName() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            when(f.server.getWorld(f.worldId)).thenReturn(null);
            assertEquals(WarpResultStatus.WORLD_NOT_LOADED, f.service.teleportToWarp(f.player, "hub", null).join());
            verify(f.server, never()).getWorld("world");
        }
    }
    @Test void uuidlessLegacyRecordsCanStillResolveByName() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            f.cache.putWarp(f.warp(null));
            assertEquals(WarpResultStatus.SUCCESS, f.service.teleportToWarp(f.player, "hub", null).join());
        }
    }
    @Test void externalPluginAndCommandTeleportsCancelPendingFunding() throws Exception {
        for (var cause : List.of(PlayerTeleportEvent.TeleportCause.PLUGIN, PlayerTeleportEvent.TeleportCause.COMMAND)) {
            try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
                CompletableFuture<Payment> funding = new CompletableFuture<>(); when(f.economy.chargeExact(any(), anyDouble(), anyString(), anyString())).thenReturn(funding);
                var result = f.service.teleportToWarp(f.player, "hub", null);
                new WarpEventListener(() -> f.service, () -> settings(false, "CHARGE_ON_WARMUP")).onPlayerTeleport(
                    new PlayerTeleportEvent(f.player, f.player.getLocation(), f.player.getLocation(), cause));
                assertEquals(WarpResultStatus.WARMUP_CANCELLED_TELEPORT, result.join());
                Payment receipt = new Payment(f.id, 12, true); funding.complete(receipt);
                verify(f.economy).settle(receipt, true); verify(f.player, never()).teleportAsync(any());
            }
        }
    }
    @Test void ownDispatchedTeleportEventDoesNotCancelItsSettlement() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            when(f.player.teleportAsync(any())).thenAnswer(call -> {
                new WarpEventListener(() -> f.service, () -> settings(false, "CHARGE_ON_WARMUP")).onPlayerTeleport(
                    new PlayerTeleportEvent(f.player, f.player.getLocation(), call.getArgument(0), PlayerTeleportEvent.TeleportCause.PLUGIN));
                return CompletableFuture.completedFuture(true);
            });
            assertEquals(WarpResultStatus.SUCCESS, f.service.teleportToWarp(f.player, "hub", null).join());
            verify(f.economy).settle(any(), eq(false));
        }
    }
    @Test void failedActiveMigrationDisablesGameplayUntilReload() throws Exception {
        try (var f = new Fixture(false, "CHARGE_ON_WARMUP")) {
            YamlWarpRepository source = new YamlWarpRepository(directory.toFile(), LoggerFactory.getLogger("source"), 8, 100, 16777216, WarpConfig.createDefault().control());
            source.initialize().join(); source.save(f.warp(f.worldId)).join(); source.close().join();
            when(f.repository.mergeSnapshot(any(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("persisted then failed verification")));
            assertThrows(RuntimeException.class, () -> f.service.migrateData("YAML", "SQLITE").join());
            assertFalse(f.service.isReady()); assertTrue(f.cache.getAllWarps().isEmpty());
            assertEquals(WarpResultStatus.NOT_READY, f.service.teleportToWarp(f.player, "hub", null).join());
        }
    }
}
