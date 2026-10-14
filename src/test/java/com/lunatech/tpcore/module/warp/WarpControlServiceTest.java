package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpConfig.WarpControlConfig;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpEdit;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpControlServiceTest {
    private WarpRepository repository;
    private WarpEconomyService economy;
    private DefaultWarpCache cache;
    private DefaultWarpService service;
    private Warp original;
    private final UUID actor = UUID.randomUUID();
    @BeforeEach void setup() {
        original = new Warp("hub", UUID.randomUUID(), "world", -5, 70, 3, 90, 0, actor, "market", null, false, 1234);
        repository = mock(WarpRepository.class); economy = mock(WarpEconomyService.class); cache = new DefaultWarpCache();
        when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of("hub", original)));
        when(repository.replace(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.archiveAndDelete(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.restore(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(economy.close()).thenReturn(CompletableFuture.completedFuture(null));
        service = new DefaultWarpService(mock(Plugin.class), repository, cache, economy, WarpConfig.createDefault(), LoggerFactory.getLogger("WarpControlServiceTest"));
        service.initialize().join();
    }
    @AfterEach void cleanup() { service.close().join(); }

    @Test void confirmationIsActorBoundSingleUseAndRequiresUnchangedMetadata() {
        assertEquals(WarpResultStatus.CONFIRMATION_REQUIRED, service.prepareDeletion(actor, "hub"));
        verify(repository, never()).archiveAndDelete(any());
        assertEquals(WarpResultStatus.CONFIRMATION_EXPIRED, service.confirmDeletion(UUID.randomUUID(), "hub").join());
        service.editWarp("hub", new WarpEdit.Category("new")).join();
        assertEquals(WarpResultStatus.CONFIRMATION_EXPIRED, service.confirmDeletion(actor, "hub").join());
        assertTrue(cache.getWarp("hub").isPresent());
        assertEquals(WarpResultStatus.CONFIRMATION_REQUIRED, service.prepareDeletion(actor, "hub"));
        assertEquals(WarpResultStatus.SUCCESS, service.confirmDeletion(actor, "hub").join());
        assertTrue(cache.getWarp("hub").isEmpty());
        assertEquals(WarpResultStatus.CONFIRMATION_EXPIRED, service.confirmDeletion(actor, "hub").join());
        verify(repository).archiveAndDelete(argThat(entry -> actor.equals(entry.deletedBy()) && entry.warp().category().equals("new")));
        verifyNoInteractions(economy);
    }

    @Test void quitClearsConfirmationAndStorageFailureKeepsActiveWarp() {
        service.prepareDeletion(actor, "hub"); service.cancelWarmupOnQuit(actor);
        assertEquals(WarpResultStatus.CONFIRMATION_EXPIRED, service.confirmDeletion(actor, "hub").join());
        service.prepareDeletion(actor, "hub");
        when(repository.archiveAndDelete(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("disk full")));
        assertEquals(WarpResultStatus.ERROR, service.confirmDeletion(actor, "hub").join());
        assertEquals(original, cache.getWarp("hub").orElseThrow());
    }

    @Test void confirmationStorageIsBoundedAndExpires() throws Exception {
        WarpConfig d = WarpConfig.createDefault();
        service.updateConfig(new WarpConfig(d.enabled(), d.warmupSeconds(), d.cooldownSeconds(), d.cancelOnMove(), d.cancelOnDamage(),
            d.defaultPermissionGated(), d.defaultCategory(), d.warpsPerPage(), d.maxWarps(), d.economyEnabled(), d.warpCost(), d.setWarpCost(),
            d.chargeTiming(), d.refundOnCancel(), d.safetyChecks(), d.storage(), d.messages(), d.maxConcurrentOperations(), d.maxCachedWarps(),
            1, d.storageQueueCapacity(), d.operationTimeoutSeconds(), d.maxStorageBytes(), d.editMessages(),
            new WarpControlConfig(1, 100, 7, 256), d.controlMessages()));
        assertEquals(WarpResultStatus.CONFIRMATION_REQUIRED, service.prepareDeletion(actor, "hub"));
        UUID other = UUID.randomUUID(); assertEquals(WarpResultStatus.BUSY, service.prepareDeletion(other, "hub"));
        Thread.sleep(1100);
        assertEquals(WarpResultStatus.CONFIRMATION_EXPIRED, service.confirmDeletion(actor, "hub").join());
        assertEquals(WarpResultStatus.CONFIRMATION_REQUIRED, service.prepareDeletion(other, "hub"));
        verify(repository, never()).archiveAndDelete(any());
    }

    @Test void closureDeniesBothTeleportPathsBeforeEconomyAndListingRemainsIndependent() {
        service.editWarp("hub", new WarpEdit.Close("Unsafe bridge")).join();
        service.editWarp("hub", new WarpEdit.Listing(true)).join();
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(actor);
        assertEquals(WarpResultStatus.WARP_CLOSED, service.teleportToWarp(player, "hub", null).join());
        assertEquals(WarpResultStatus.WARP_CLOSED, service.teleportOtherToWarp(null, player, "hub").join());
        service.editWarp("hub", new WarpEdit.Rename("renamed")).join();
        Warp renamed = cache.getWarp("renamed").orElseThrow(); assertTrue(renamed.closed()); assertTrue(renamed.unlisted()); assertEquals("Unsafe bridge", renamed.closureReason());
        service.editWarp("renamed", new WarpEdit.Open()).join();
        assertFalse(cache.getWarp("renamed").orElseThrow().closed()); assertTrue(cache.getWarp("renamed").orElseThrow().unlisted());
        verifyNoInteractions(economy);
    }

    @Test void recoveryDoesNotOverwriteExistingNamesOrChargeAndReleasesGuards() {
        DeletedWarp entry = new DeletedWarp(UUID.randomUUID(), original, System.currentTimeMillis(), actor);
        when(repository.loadDeleted()).thenReturn(CompletableFuture.completedFuture(List.of(entry)));
        assertEquals(WarpResultStatus.ALREADY_EXISTS, service.restoreWarp(entry.id()).join());
        verify(repository, never()).restore(any());
        cache.removeWarp("hub"); assertEquals(WarpResultStatus.SUCCESS, service.restoreWarp(entry.id()).join());
        assertEquals(original, cache.getWarp("hub").orElseThrow());
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("hub", new WarpEdit.Close("test")).join());
        verifyNoInteractions(economy);
    }
}
