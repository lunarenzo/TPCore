package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpConfig.WarpSafetyConfig;
import com.lunatech.tpcore.config.model.WarpPolicyConfig;
import com.lunatech.tpcore.config.model.WarpPolicyConfig.WorldRule;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService.Payment;
import com.lunatech.tpcore.module.warp.listener.WarpEventListener;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpPolicyServiceTest {
    @Test void fundingQueueRejectionAndProviderFailureRemainDistinct() throws Exception {
        for (boolean busy : new boolean[] {true, false}) {
            try (var f = new Fixture(policy(12, 3, 5, WorldRule.unrestricted(), false, false))) {
                RuntimeException failure = busy ? new RejectedExecutionException("queue full") : new IllegalStateException("provider unavailable");
                when(f.economy.chargeExact(eq(f.id), anyDouble())).thenReturn(CompletableFuture.failedFuture(failure));
                assertEquals(busy ? WarpResultStatus.BUSY : WarpResultStatus.ECONOMY_UNAVAILABLE,
                    f.service.teleportToWarp(f.player, "hub", null).get(2, TimeUnit.SECONDS));
                assertEquals(0, f.service.diagnostics().pendingOperations());
                verify(f.player, never()).teleportAsync(any());
            }
        }
    }

    @Test void statusShowsAnAdmittedWarmupAndReleasesItsCapacityOnQuit() throws Exception {
        try (var f = new Fixture(policy(12, 3, 5, WorldRule.unrestricted(), false, false))) {
            var result = f.service.teleportToWarp(f.player, "hub", null);
            assertEquals(1, f.service.diagnostics().activeTeleports());
            assertEquals(1, f.service.diagnostics().pendingOperations());
            f.service.cancelWarmupOnQuit(f.id);
            assertTrue(result.isDone());
            assertEquals(0, f.service.diagnostics().pendingOperations());
        }
    }
    private WarpPolicyConfig policy(double price, int warmup, int cooldown, WorldRule source, boolean combat, boolean hook) {
        var d = WarpPolicyConfig.createDefault();
        return new WarpPolicyConfig(Map.of("hub", new WarpPolicyConfig.Override(price, warmup, cooldown)), Map.of(),
            source, source, WorldRule.unrestricted(), combat, 10, true, hook, false, d.messages());
    }

    private WarpConfig settings(WarpPolicyConfig policy) {
        var d = WarpConfig.createDefault();
        return new WarpConfig(d.enabled(), d.warmupSeconds(), d.cooldownSeconds(), d.cancelOnMove(), false, d.defaultPermissionGated(),
            d.defaultCategory(), d.warpsPerPage(), d.maxWarps(), true, 0, 0, "CHARGE_ON_WARMUP", false,
            new WarpSafetyConfig(false, false, 128), d.storage(), d.messages(), d.maxConcurrentOperations(), d.maxCachedWarps(),
            d.maxPlayerStateEntries(), d.storageQueueCapacity(), d.operationTimeoutSeconds(), d.maxStorageBytes(),
            d.editMessages(), d.control(), d.controlMessages(), d.browsing(), d.essentialsImport(), policy);
    }

    private final class Fixture implements AutoCloseable {
        final UUID id = UUID.randomUUID(), worldId = UUID.randomUUID();
        final Player player = mock(Player.class);
        final EntityScheduler scheduler = mock(EntityScheduler.class);
        final WarpEconomyService economy = mock(WarpEconomyService.class);
        final WarpRepository repository = mock(WarpRepository.class);
        final DefaultWarpCache cache = new DefaultWarpCache();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final DefaultWarpService service;
        Consumer<ScheduledTask> delayed;

        Fixture(WarpPolicyConfig policies) {
            World world = mock(World.class); when(world.getUID()).thenReturn(worldId); when(world.getName()).thenReturn("world");
            var server = mock(Server.class);
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            when(player.getUniqueId()).thenReturn(id); when(player.getLocation()).thenReturn(new Location(world, .5, 64, .5));
            when(player.isOnline()).thenReturn(true);
            when(player.getScheduler()).thenReturn(scheduler); when(player.teleportAsync(any())).thenReturn(CompletableFuture.completedFuture(true));
            doAnswer(call -> { Consumer<ScheduledTask> action = call.getArgument(1); action.accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class); }).when(scheduler).run(any(), any(), any());
            doAnswer(call -> { delayed = call.getArgument(1); return mock(ScheduledTask.class); }).when(scheduler).runDelayed(any(), any(), any(), anyLong());
            when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(repository.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of()));
            when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
            when(economy.isAvailable()).thenReturn(true); when(economy.format(anyDouble())).thenAnswer(call -> "quoted:" + call.getArgument(0));
            when(economy.chargeExact(eq(id), anyDouble())).thenAnswer(call -> CompletableFuture.completedFuture(new Payment(id, call.getArgument(1), true)));
            when(economy.settle(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(true));
            when(economy.close()).thenReturn(CompletableFuture.completedFuture(null));
            service = new DefaultWarpService(mock(Plugin.class), repository, cache, economy, settings(policies), LoggerFactory.getLogger("WarpPolicyTest"));
            service.initialize().join(); cache.putWarp(new Warp("hub", worldId, "world", .5, 64, .5, 0, 0, id, "general", null, false, 0));
        }
        @Override public void close() throws Exception { try { service.close().get(2, TimeUnit.SECONDS); } finally { bukkit.close(); } }
    }

    @Test void effectiveOverrideIsQuotedBeforeFundingAndSuccessfulCooldownUsesThatOverride() throws Exception {
        try (var f = new Fixture(policy(12, 0, 25, WorldRule.unrestricted(), false, false))) {
            assertEquals(WarpResultStatus.SUCCESS, f.service.teleportToWarp(f.player, "hub", null).get(2, TimeUnit.SECONDS));
            var order = inOrder(f.player, f.economy);
            order.verify(f.player).sendMessage(any(Component.class)); order.verify(f.economy).chargeExact(f.id, 12.0);
            assertTrue(f.service.getRemainingCooldownSeconds(f.id) > 0 && f.service.getRemainingCooldownSeconds(f.id) <= 25);
        }
    }

    @Test void reloadDoesNotChangeAcceptedWarmupCooldownOrReceipt() throws Exception {
        try (var f = new Fixture(policy(12, 3, 5, WorldRule.unrestricted(), false, false))) {
            CompletableFuture<Payment> pending = new CompletableFuture<>(); when(f.economy.chargeExact(f.id, 12.0)).thenReturn(pending);
            var result = f.service.teleportToWarp(f.player, "hub", null);
            f.service.updateConfig(settings(policy(999, 7, 70, WorldRule.unrestricted(), false, false)));
            Payment receipt = new Payment(f.id, 5, true); pending.complete(receipt);
            verify(f.scheduler).runDelayed(any(), any(), any(), eq(60L));
            f.delayed.accept(mock(ScheduledTask.class));
            assertEquals(WarpResultStatus.SUCCESS, result.get(2, TimeUnit.SECONDS));
            verify(f.economy).settle(receipt, false); verify(f.economy, never()).chargeExact(f.id, 999);
            assertTrue(f.service.getRemainingCooldownSeconds(f.id) <= 5);
        }
    }

    @Test void newWorldRestrictionDuringWarmupBlocksDepartureAndRefundsEvenWithRefundOnCancelFalse() throws Exception {
        try (var f = new Fixture(policy(12, 3, 5, WorldRule.unrestricted(), false, false))) {
            var result = f.service.teleportToWarp(f.player, "hub", null);
            f.service.updateConfig(settings(policy(12, 3, 5, new WorldRule(Set.of(), Set.of("world")), false, false)));
            f.delayed.accept(mock(ScheduledTask.class));
            assertEquals(WarpResultStatus.WORLD_RESTRICTED, result.get(2, TimeUnit.SECONDS));
            verify(f.economy).settle(new Payment(f.id, 12, true), true); verify(f.player, never()).teleportAsync(any());
        }
    }

    @Test void deniedCreationAndSourceWorldsNeverRequestPayment() throws Exception {
        try (var f = new Fixture(policy(12, 0, 0, new WorldRule(Set.of("event"), Set.of()), false, false))) {
            assertEquals(WarpResultStatus.WORLD_RESTRICTED, f.service.teleportToWarp(f.player, "hub", null).join());
            assertEquals(WarpResultStatus.WORLD_RESTRICTED, f.service.setWarp(f.player, "new", false, null, null).join());
            verify(f.economy, never()).chargeExact(any(), anyDouble());
        }
    }

    @Test void combatPreventsStartingBetweenAttacksEvenWithCancelOnDamageDisabled() throws Exception {
        try (var f = new Fixture(policy(12, 3, 5, WorldRule.unrestricted(), true, false))) {
            f.service.recordCombat(f.id);
            assertEquals(WarpResultStatus.COMBAT_TAGGED, f.service.teleportToWarp(f.player, "hub", null).join());
            verify(f.economy, never()).chargeExact(any(), anyDouble());
        }
    }

    @Test void combatDuringWarmupRefundsAndPreventsTeleport() throws Exception {
        try (var f = new Fixture(policy(12, 3, 5, WorldRule.unrestricted(), true, false))) {
            var result = f.service.teleportToWarp(f.player, "hub", null); f.service.recordCombat(f.id);
            assertEquals(WarpResultStatus.COMBAT_TAGGED, result.get(2, TimeUnit.SECONDS));
            verify(f.economy).settle(new Payment(f.id, 12, true), true); verify(f.player, never()).teleportAsync(any());
        }
    }

    @Test void enabledMissingProtectionFailsClosedBeforePayment() throws Exception {
        try (var f = new Fixture(policy(12, 0, 0, WorldRule.unrestricted(), false, true))) {
            assertEquals(WarpResultStatus.PROTECTION_DENIED, f.service.teleportToWarp(f.player, "hub", null).join());
            verify(f.economy, never()).chargeExact(any(), anyDouble());
        }
    }

    @Test void positivePvpDamageTagsBothActorsWithoutRequiringCancelOnDamage() {
        var config = settings(policy(0, 0, 0, WorldRule.unrestricted(), true, false));
        WarpService service = mock(WarpService.class);
        Player victim = mock(Player.class), attacker = mock(Player.class);
        UUID victimId = UUID.randomUUID(), attackerId = UUID.randomUUID();
        when(victim.getUniqueId()).thenReturn(victimId); when(attacker.getUniqueId()).thenReturn(attackerId);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim); when(event.getDamager()).thenReturn(attacker); when(event.getFinalDamage()).thenReturn(1.0);
        new WarpEventListener(() -> service, () -> config).onEntityDamage(event);
        verify(service).recordCombat(victimId); verify(service).recordCombat(attackerId);
    }

    @Test void permissionRevokedDuringWarmupDeniesDepartureAndRefundsReceipt() throws Exception {
        try (var f = new Fixture(policy(12, 3, 5, WorldRule.unrestricted(), false, false))) {
            Warp existing = f.cache.getWarp("hub").orElseThrow();
            f.cache.putWarp(new Warp(existing.name(), existing.worldId(), existing.worldName(), existing.x(), existing.y(), existing.z(),
                existing.yaw(), existing.pitch(), existing.creatorUuid(), existing.category(), null, true, existing.createdAt()));
            when(f.player.hasPermission("tpcore.warp.hub")).thenReturn(true);
            var result = f.service.teleportToWarp(f.player, "hub", null);
            when(f.player.hasPermission("tpcore.warp.hub")).thenReturn(false);
            f.delayed.accept(mock(ScheduledTask.class));
            assertEquals(WarpResultStatus.NO_PERMISSION, result.get(2, TimeUnit.SECONDS));
            verify(f.economy).settle(new Payment(f.id, 12, true), true); verify(f.player, never()).teleportAsync(any());
        }
    }
}
