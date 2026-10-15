package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService.Payment;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpEdit;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpConcurrencyRegressionTest {
    private final UUID id = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private Player player;
    private EntityScheduler scheduler;
    private WarpRepository repository;
    private WarpEconomyService economy;
    private DefaultWarpCache cache;
    private DefaultWarpService service;
    private CompletableFuture<Payment> payment;

    @BeforeEach void setup() throws Exception {
        Server server = mock(Server.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(server.getWorld(worldId)).thenReturn(world);
        when(server.getWorld("world")).thenReturn(world);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 1));
        when(server.getPlayer(id)).thenReturn(player);
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, server);
        scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            Consumer<ScheduledTask> action = invocation.getArgument(1);
            action.accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class);
        }).when(scheduler).run(any(), any(), any());
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenReturn(mock(ScheduledTask.class));
        repository = mock(WarpRepository.class);
        when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(repository.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
        economy = mock(WarpEconomyService.class);
        when(economy.isAvailable()).thenReturn(true);
        when(economy.getSetWarpCost(player)).thenReturn(50.0);
        payment = new CompletableFuture<>();
        when(economy.chargeExact(eq(id), eq(50.0), anyString(), anyString())).thenReturn(payment);
        when(economy.settle(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(true));
        when(economy.close()).thenReturn(CompletableFuture.completedFuture(null));
        cache = new DefaultWarpCache();
        service = new DefaultWarpService(mock(Plugin.class), repository, cache, economy,
            paidConfig(50), LoggerFactory.getLogger("WarpConcurrencyRegression"));
    }

    private WarpConfig paidConfig(double price) {
        WarpConfig d = WarpConfig.createDefault();
        return new WarpConfig(true, 3, 10, true, true, false, "general", 8, 100, true, price, 50,
            d.chargeTiming(), d.refundOnCancel(), d.safetyChecks(), d.storage(), d.messages());
    }

    private void ready() {
        service.initialize().join();
        cache.putWarp(new Warp("hub", worldId, "world", 0, 64, 0, 0, 0, id, "general", null, false, 0));
    }

    @AfterEach void cleanup() throws Exception {
        if (!payment.isDone()) payment.complete(new Payment(id, 0, false));
        service.close().get(2, TimeUnit.SECONDS);
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, null);
    }

    @Test void requestsAreRejectedUntilInitializationCompletes() {
        assertEquals(WarpResultStatus.NOT_READY, service.setWarp(player, "first", false, null, null).join());
        verify(repository, never()).save(any());
    }

    @Test void closingDuringPendingChargeRefundsLatePaymentWithoutStartingWarmup() {
        ready();
        when(repository.replace(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<WarpResultStatus> result = service.teleportToWarp(player, "hub", null);
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("hub", new WarpEdit.Close("Unsafe bridge")).join());
        assertEquals(WarpResultStatus.WARP_CLOSED, result.join());
        Payment receipt = new Payment(id, 25, true); payment.complete(receipt);
        verify(economy).settle(receipt, true);
        verify(scheduler, never()).runDelayed(any(), any(), any(), anyLong());
        verify(player, never()).teleportAsync(any());
    }

    @Test void closingPaidWarmupRefundsEvenWhenOrdinaryCancellationRefundsAreDisabled() {
        WarpConfig defaults = WarpConfig.createDefault();
        service.updateConfig(new WarpConfig(true, 3, 10, true, true, false, "general", 8, 100, true, 50, 50,
            "CHARGE_ON_WARMUP", false, defaults.safetyChecks(), defaults.storage(), defaults.messages()));
        ready();
        when(repository.replace(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<WarpResultStatus> result = service.teleportToWarp(player, "hub", null);
        Payment receipt = new Payment(id, 25, true); payment.complete(receipt);
        service.editWarp("hub", new WarpEdit.Close("Unsafe bridge")).join();
        assertEquals(WarpResultStatus.WARP_CLOSED, result.join());
        verify(economy).settle(receipt, true);
        verify(player, never()).teleportAsync(any());
    }

    @Test void failedClosureRefundReportsFailureAndCannotDispatchTeleport() {
        ready();
        when(repository.replace(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<WarpResultStatus> result = service.teleportToWarp(player, "hub", null);
        Payment receipt = new Payment(id, 25, true); payment.complete(receipt);
        when(economy.settle(receipt, true)).thenReturn(CompletableFuture.completedFuture(false));
        service.editWarp("hub", new WarpEdit.Close("Unsafe bridge")).join();
        assertEquals(WarpResultStatus.ERROR, result.join());
        verify(economy).settle(receipt, true); verify(player, never()).teleportAsync(any());
        assertTrue(cache.getWarp("hub").orElseThrow().closed());
    }

    @Test void duplicateAndCancelledPendingPaymentsKeepTheirReservation() {
        ready();
        CompletableFuture<WarpResultStatus> first = service.teleportToWarp(player, "hub", null);
        assertEquals(WarpResultStatus.WARMUP_ALREADY_ACTIVE, service.teleportToWarp(player, "hub", null).join());
        service.cancelWarmupOnMove(player);
        assertEquals(WarpResultStatus.WARMUP_CANCELLED_MOVE, first.join());
        assertEquals(WarpResultStatus.WARMUP_ALREADY_ACTIVE, service.teleportToWarp(player, "hub", null).join());
        Payment receipt = new Payment(id, 25.0, true);
        payment.complete(receipt);
        verify(economy).settle(receipt, true);
        verify(economy, times(1)).chargeExact(eq(id), eq(50.0), anyString(), anyString());
        verify(scheduler, never()).runDelayed(any(), any(), any(), anyLong());
    }

    @Test void cancellationCompletesAndRefundsTheReceiptRatherThanTheCurrentPrice() {
        ready();
        CompletableFuture<WarpResultStatus> result = service.teleportToWarp(player, "hub", null);
        Payment receipt = new Payment(id, 25.0, true);
        payment.complete(receipt);
        service.updateConfig(paidConfig(500));
        service.cancelWarmupOnDamage(player);
        assertEquals(WarpResultStatus.WARMUP_CANCELLED_DAMAGE, result.join());
        verify(economy).settle(receipt, true);
    }

    @Test void creationSnapshotsThePlayerBeforePaymentCompletes() {
        service.initialize().join();
        CompletableFuture<WarpResultStatus> result = service.setWarp(player, "snapshot", false, null, null);
        verify(player, times(1)).getLocation();
        when(player.getLocation()).thenThrow(new IllegalStateException("Player state read on payment completion"));
        payment.complete(new Payment(id, 50.0, true));
        assertEquals(WarpResultStatus.SUCCESS, result.join());
        assertEquals(64.0, cache.getWarp("snapshot").orElseThrow().y());
    }

    @Test void rejectedEntitySchedulingCompletesInsteadOfHanging() {
        ready();
        doReturn(null).when(scheduler).run(any(), any(), any());
        CompletableFuture<WarpResultStatus> result = service.teleportToWarp(player, "hub", null);
        Payment receipt = new Payment(id, 50.0, true);
        payment.complete(receipt);
        assertEquals(WarpResultStatus.ERROR, result.join());
        verify(economy).settle(receipt, true);
    }

    @Test void lateInitializationCannotRepopulateAClosedCache() {
        CompletableFuture<Map<String, Warp>> loading = new CompletableFuture<>();
        when(repository.loadAll()).thenReturn(loading);
        CompletableFuture<Void> initialized = service.initialize();
        service.close().join();
        loading.complete(Map.of("late", new Warp("late", worldId, "world", 0, 64, 0, 0, 0, id, "general", null, false, 0)));
        assertTrue(initialized.isCompletedExceptionally());
        assertEquals(0, cache.getWarpCount());
        assertFalse(service.isReady());
    }

    @Test void paidCreationAfterCloseRefundsWithoutWritingOrPublishing() {
        service.initialize().join();
        CompletableFuture<WarpResultStatus> result = service.setWarp(player, "late", false, null, null);
        CompletableFuture<Void> closing = service.close();
        Payment receipt = new Payment(id, 50.0, true);
        payment.complete(receipt);
        assertEquals(WarpResultStatus.ERROR, result.join());
        closing.join();
        verify(repository, never()).save(any());
        verify(economy).settle(receipt, true);
        assertEquals(0, cache.getWarpCount());
    }

    @Test void concurrentCreatesReserveTheLastAvailableSlotBeforePayment() {
        service.initialize().join();
        for (int index = 0; index < 99; index++) {
            cache.putWarp(new Warp("existing" + index, worldId, "world", 0, 64, 0, 0, 0, id, "general", null, false, 0));
        }
        CompletableFuture<WarpResultStatus> first = service.setWarp(player, "last", false, null, null);
        assertEquals(WarpResultStatus.LIMIT_REACHED, service.setWarp(player, "overflow", false, null, null).join());
        verify(economy, times(1)).chargeExact(eq(id), eq(50.0), anyString(), anyString());
        payment.complete(new Payment(id, 50.0, true));
        assertEquals(WarpResultStatus.SUCCESS, first.join());
        assertEquals(100, cache.getWarpCount());
    }
}
