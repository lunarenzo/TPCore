package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.cache.impl.LockFreeCandidateBuffer;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpConfigManager;
import com.lunatech.tpcore.module.rtp.repository.RtpCacheRepository;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.slf4j.LoggerFactory;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class AdaptiveRtpReplenisherTest {

    @Test
    @DisplayName("Verify LockFreeCandidateBuffer EMPTY_SENTINEL produces null candidate instead of void teleportation")
    void testEmptyBufferSentinelResolution() {
        LockFreeCandidateBuffer buffer = new LockFreeCandidateBuffer(16);
        long polled = buffer.poll();

        Assertions.assertEquals(LockFreeCandidateBuffer.EMPTY_SENTINEL, polled);
        Assertions.assertNotEquals(-1L, polled);
    }
    @Test void immediatelyCompletedRestoreIsPublishedBeforeItCanBeConsumed() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var server = mock(Server.class); var world = mock(World.class); var id = UUID.randomUUID();
            when(world.getUID()).thenReturn(id); when(world.getName()).thenReturn("world");
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world)); bukkit.when(Bukkit::getServer).thenReturn(server);
            var scheduler = mock(GlobalRegionScheduler.class); bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            var configs = mock(RtpConfigManager.class); var config = RtpConfig.createDefault();
            when(configs.getActiveConfig()).thenReturn(new RtpConfig(true, 2, 45, 15, config.worldConfigs(), config.messages()));
            var repository = mock(RtpCacheRepository.class); var inspector = mock(RtpSafetyInspector.class);
            long first = PackedLocation.pack(200, 70, 0), second = PackedLocation.pack(210, 70, 0);
            when(repository.loadPackedLocations(id)).thenReturn(CompletableFuture.completedFuture(List.of(first, second)));
            when(repository.deletePackedLocation(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(null));
            when(scheduler.runAtFixedRate(any(), any(), anyLong(), anyLong())).thenAnswer(call -> {
                Consumer<ScheduledTask> pass = call.getArgument(1); pass.accept(null); return mock(ScheduledTask.class);
            });
            var replenisher = new AdaptiveRtpReplenisher(mock(Plugin.class), configs, inspector, repository, LoggerFactory.getLogger(getClass()));
            replenisher.start();
            assertEquals(200.5, replenisher.popCandidate(world).x());
            assertEquals(210.5, replenisher.popCandidate(world).x());
            assertNull(replenisher.popCandidate(world));
            verifyNoInteractions(inspector);
            replenisher.stop();
        }
    }

}
