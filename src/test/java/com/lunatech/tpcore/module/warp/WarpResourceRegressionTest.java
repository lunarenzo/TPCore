package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.cache.impl.BoundedExpiryCache;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.repository.impl.WarpRefundJournal;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.util.WarpIoExecutor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.bukkit.plugin.Plugin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class WarpResourceRegressionTest {
    @TempDir Path directory;

    @Test void ioQueueRejectsOverflowAndDrainsAcceptedWorkBeforeCleanup() throws Exception {
        WarpIoExecutor io = new WarpIoExecutor(1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = io.run(() -> {
            running.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        CompletableFuture<Integer> second = io.submit(() -> 42);
        assertTrue(io.submit(() -> 43).isCompletedExceptionally());
        CompletableFuture<Void> close = io.close(() -> assertTrue(second.isDone()));
        assertFalse(close.isDone());
        release.countDown(); first.get(2, TimeUnit.SECONDS);
        assertEquals(42, second.get(2, TimeUnit.SECONDS)); close.get(2, TimeUnit.SECONDS);
        assertTrue(io.submit(() -> 44).isCompletedExceptionally());
    }

    @Test void expirationCapacityDoesNotEvictLiveCooldowns() {
        BoundedExpiryCache state = new BoundedExpiryCache(1);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(state.put(first, Long.MAX_VALUE));
        assertFalse(state.put(second, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, state.get(first));
        state.remove(first);
        assertTrue(state.put(second, Long.MAX_VALUE));
    }

    @Test void failedYamlWritesAndReadsCompleteExceptionally() throws Exception {
        File data = directory.toFile();
        YamlWarpRepository repo = new YamlWarpRepository(data, LoggerFactory.getLogger("YamlFailure"));
        repo.initialize().join();
        Path file = directory.resolve("warps/warps.yml");
        Files.createDirectory(file);
        Warp warp = new Warp("test", UUID.randomUUID(), "world", 0, 64, 0, 0, 0, UUID.randomUUID(), "general", null, false, 0);
        assertThrows(RuntimeException.class, () -> repo.save(warp).join());
        assertThrows(RuntimeException.class, () -> repo.loadAll().join());
        repo.close().get(2, TimeUnit.SECONDS);
    }

    @Test void unresolvedRefundsSurviveRestartWithoutAutomaticReplay() throws Exception {
        Path path = directory.resolve("refunds.properties");
        WarpRefundJournal first = new WarpRefundJournal(path, 2, 2);
        first.ready().join();
        UUID ticket = first.record(UUID.randomUUID(), 25.0).join();
        first.close().get(2, TimeUnit.SECONDS);
        WarpRefundJournal reopened = new WarpRefundJournal(path, 2, 2);
        reopened.ready().join();
        assertEquals(1, reopened.pending());
        reopened.complete(ticket).join();
        assertEquals(0, reopened.pending());
        reopened.close().get(2, TimeUnit.SECONDS);
    }

    @Test void migrationPreservesDestinationOnlyWarpsAndRejectsConcurrentMutations() throws Exception {
        var logger = LoggerFactory.getLogger("WarpMigrationRegression");
        Warp incoming = new Warp("incoming", UUID.randomUUID(), "world", 0, 64, 0, 0, 0, UUID.randomUUID(), "general", null, false, 0);
        Warp existing = new Warp("existing", UUID.randomUUID(), "world", 0, 64, 0, 0, 0, UUID.randomUUID(), "general", null, false, 0);
        YamlWarpRepository source = new YamlWarpRepository(directory.toFile(), logger);
        source.initialize().join(); source.save(incoming).join(); source.close().get(2, TimeUnit.SECONDS);
        WarpRepository target = mock(WarpRepository.class);
        when(target.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(target.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of("existing", existing)),
            CompletableFuture.completedFuture(Map.of("existing", existing)),
            CompletableFuture.completedFuture(Map.of("existing", existing, "incoming", incoming)));
        CompletableFuture<Void> writing = new CompletableFuture<>();
        when(target.mergeSnapshot(any(), any())).thenReturn(writing);
        when(target.close()).thenReturn(CompletableFuture.completedFuture(null));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        DefaultWarpCache cache = new DefaultWarpCache();
        DefaultWarpService service = new DefaultWarpService(plugin, target, cache, WarpConfig.createDefault(), logger);
        try {
            service.initialize().join();
            CompletableFuture<Integer> migration = service.migrateData("YAML", "SQLITE");
            assertEquals(WarpResultStatus.BUSY, service.deleteWarp("existing").join());
            assertTrue(service.migrateData("YAML", "SQLITE").isCompletedExceptionally());
            writing.complete(null);
            assertEquals(1, migration.get(3, TimeUnit.SECONDS));
            assertTrue(cache.getWarp("existing").isPresent());
            assertTrue(cache.getWarp("incoming").isPresent());
        } finally {
            writing.complete(null);
            service.close().get(3, TimeUnit.SECONDS);
        }
    }
}
