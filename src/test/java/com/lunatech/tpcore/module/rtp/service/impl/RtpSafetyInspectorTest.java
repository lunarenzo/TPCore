package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RtpSafetyInspectorTest {
    private void server(Server server) throws Exception {
        var field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, server);
    }
    @AfterEach void clear() throws Exception { server(null); }
    @Test void completedChunkFutureStillDefersAllBlockReadsToTheRegionOwner() throws Exception {
        var server = mock(Server.class); server(server);
        var scheduler = mock(RegionScheduler.class); when(server.getRegionScheduler()).thenReturn(scheduler);
        var queue = new ArrayDeque<Runnable>();
        doAnswer(call -> { queue.add(call.getArgument(4)); return null; })
            .when(scheduler).execute(any(), any(World.class), anyInt(), anyInt(), any());
        var world = mock(World.class); var chunk = mock(Chunk.class); var border = mock(WorldBorder.class);
        when(world.getChunkAtAsync(12, 0, false)).thenReturn(CompletableFuture.completedFuture(chunk));
        when(world.isChunkLoaded(12, 0)).thenReturn(true);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getWorldBorder()).thenReturn(border); when(border.isInside(any())).thenReturn(true);
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(64);
        when(world.getMaxHeight()).thenReturn(256); when(chunk.getX()).thenReturn(12); when(chunk.getZ()).thenReturn(0);
        var stone = spy(Material.STONE); doReturn(true).when(stone).isSolid(); doReturn(false).when(stone).isAir();
        var solid = mock(Block.class); when(solid.getType()).thenReturn(stone);
        var airMaterial = spy(Material.AIR); doReturn(true).when(airMaterial).isAir();
        var air = mock(Block.class); when(air.getType()).thenReturn(airMaterial);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call -> ((int) call.getArgument(1)) == 64 ? solid : air);
        var inspector = new RtpSafetyInspector(mock(Plugin.class));
        var result = inspector.inspectCandidate(world, RtpConfig.createDefault().worldConfigs().get("world"), 200, 8, false);
        assertFalse(result.isDone()); verifyNoInteractions(chunk);
        queue.remove().run(); assertNotNull(result.join());
    }
    @Test void unresolvedChunkWorkKeepsItsBoundAfterObserversTimeOut() {
        var world = mock(World.class);
        when(world.getChunkAtAsync(anyInt(), anyInt(), anyBoolean())).thenAnswer(call -> new CompletableFuture<Chunk>());
        var inspector = new RtpSafetyInspector(mock(Plugin.class));
        var config = RtpConfig.createDefault().worldConfigs().get("world");
        for (int index = 0; index < 32; index++) assertFalse(inspector.inspectCandidate(world, config, 200, index, false).isDone());
        assertNull(inspector.inspectCandidate(world, config, 200, 100, false).join());
        verify(world, times(32)).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
    }
}
