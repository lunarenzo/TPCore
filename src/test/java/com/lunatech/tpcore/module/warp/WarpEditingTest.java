package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpEdit;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
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

class WarpEditingTest {
    private WarpRepository repository;
    private WarpEconomyService economy;
    private DefaultWarpCache cache;
    private DefaultWarpService service;
    private Warp original;

    @BeforeEach void setup() {
        original = new Warp("old", UUID.randomUUID(), "world", 12.5, 64, -20.5, 90, 15,
            UUID.randomUUID(), "general", null, false, 12345);
        repository = mock(WarpRepository.class);
        when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of("old", original)));
        when(repository.replace(anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
        economy = mock(WarpEconomyService.class);
        when(economy.close()).thenReturn(CompletableFuture.completedFuture(null));
        cache = new DefaultWarpCache();
        service = new DefaultWarpService(mock(Plugin.class), repository, cache, economy, WarpConfig.createDefault(), LoggerFactory.getLogger("WarpEditingTest"));
        service.initialize().join();
    }

    @AfterEach void cleanup() { service.close().join(); }

    @Test void metadataEditsPreserveLocationOwnershipAndTimestampWithoutCharging() {
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("old", new WarpEdit.Category("Market")).join());
        Warp category = cache.getWarp("old").orElseThrow();
        assertEquals("market", category.category());
        assertEquals(original, new Warp(category.name(), category.worldId(), category.worldName(), category.x(), category.y(), category.z(),
            category.yaw(), category.pitch(), category.creatorUuid(), original.category(), category.passwordHash(), category.permissionGated(), category.createdAt()));
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("old", new WarpEdit.Permission(true)).join());
        Warp restricted = cache.getWarp("old").orElseThrow();
        assertTrue(restricted.permissionGated()); assertEquals("market", restricted.category());
        assertEquals(original.createdAt(), restricted.createdAt()); assertEquals(original.creatorUuid(), restricted.creatorUuid());
        verifyNoInteractions(economy);
    }

    @Test void relocationPreservesMetadataAndAcceptsImmutableCoordinates() {
        UUID world = UUID.randomUUID();
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("old", new WarpEdit.Relocate(world, "other", -42, 70, 28, 180, -30)).join());
        Warp relocated = cache.getWarp("old").orElseThrow();
        assertEquals(original.withLocation("other", world, -42, 70, 28, 180, -30), relocated);
        verifyNoInteractions(economy);
    }

    @Test void passwordsSurviveRepeatedRenamesAndCanBeReplacedOrCleared() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        service.editWarp("old", new WarpEdit.Password("secret")).join();
        assertEquals(WarpResultStatus.WORLD_NOT_LOADED, service.teleportToWarp(player, "old", "secret").join());
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("old", new WarpEdit.Rename("new")).join());
        assertTrue(cache.getWarp("old").isEmpty());
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("new", new WarpEdit.Rename("latest")).join());
        assertEquals(WarpResultStatus.WORLD_NOT_LOADED, service.teleportToWarp(player, "latest", "secret").join());
        service.editWarp("latest", new WarpEdit.Password("replacement")).join();
        assertEquals(WarpResultStatus.INVALID_PASSWORD, service.teleportToWarp(player, "latest", "secret").join());
        service.cancelWarmupOnQuit(player.getUniqueId());
        assertEquals(WarpResultStatus.WORLD_NOT_LOADED, service.teleportToWarp(player, "latest", "replacement").join());
        service.editWarp("latest", new WarpEdit.ClearPassword()).join();
        assertFalse(cache.getWarp("latest").orElseThrow().hasPassword());
        assertEquals(WarpResultStatus.WORLD_NOT_LOADED, service.teleportToWarp(player, "latest", null).join());
        verifyNoInteractions(economy);
    }

    @Test void nameCollisionNeverReplacesTheDestinationWarp() {
        Warp target = new Warp("target", original.worldId(), "world", 1, 2, 3, 0, 0, original.creatorUuid(), "general", null, false, 1);
        cache.putWarp(target);
        assertEquals(WarpResultStatus.ALREADY_EXISTS, service.editWarp("old", new WarpEdit.Rename("TARGET")).join());
        assertEquals(original, cache.getWarp("old").orElseThrow());
        assertEquals(target, cache.getWarp("target").orElseThrow());
        verify(repository, never()).replace(anyString(), any());
    }

    @Test void storageFailureDoesNotPublishAnEdit() {
        when(repository.replace(anyString(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("disk unavailable")));
        assertEquals(WarpResultStatus.ERROR, service.editWarp("old", new WarpEdit.Rename("new")).join());
        assertEquals(original, cache.getWarp("old").orElseThrow()); assertTrue(cache.getWarp("new").isEmpty());
    }

    @Test void pendingRenameReservesBothNamesAndExcludesMigration() {
        CompletableFuture<Void> writing = new CompletableFuture<>();
        when(repository.replace(anyString(), any())).thenReturn(writing);
        CompletableFuture<WarpResultStatus> rename = service.editWarp("old", new WarpEdit.Rename("new"));
        try {
            assertEquals(WarpResultStatus.BUSY, service.editWarp("old", new WarpEdit.Category("other")).join());
            assertEquals(WarpResultStatus.BUSY, service.setWarp(mock(Player.class), "new", false, null, null).join());
            assertTrue(service.migrateData("SQLITE", "YAML").isCompletedExceptionally());
        } finally { writing.complete(null); }
        assertEquals(WarpResultStatus.SUCCESS, rename.join());
        assertEquals(WarpResultStatus.SUCCESS, service.editWarp("new", new WarpEdit.Category("other")).join());
    }

    @Test void invalidValuesDoNotTouchPersistence() {
        assertEquals(WarpResultStatus.INVALID_NAME, service.editWarp("old", new WarpEdit.Rename("bad name")).join());
        assertEquals(WarpResultStatus.INVALID_VALUE, service.editWarp("old", new WarpEdit.Password(" ")).join());
        assertEquals(WarpResultStatus.INVALID_VALUE, service.editWarp("old", new WarpEdit.Category(" ")).join());
        assertEquals(WarpResultStatus.INVALID_VALUE, service.editWarp("old", new WarpEdit.Relocate(original.worldId(), "world", Double.NaN, 64, 0, 0, 0)).join());
        verify(repository, never()).replace(anyString(), any());
    }

    @Test void cacheReplacementWorksAtCapacityAndReindexesCategories() {
        DefaultWarpCache bounded = new DefaultWarpCache(); bounded.setCapacity(1); bounded.putWarp(original);
        Warp updated = new Warp("new", original.worldId(), original.worldName(), original.x(), original.y(), original.z(), original.yaw(), original.pitch(),
            original.creatorUuid(), "market", original.passwordHash(), original.permissionGated(), original.createdAt());
        bounded.replaceWarp("old", updated);
        assertEquals(1, bounded.getWarpCount()); assertTrue(bounded.getWarp("old").isEmpty());
        assertTrue(bounded.getWarpsByCategory("general").isEmpty()); assertEquals(updated, bounded.getWarpsByCategory("market").getFirst());
    }
}
