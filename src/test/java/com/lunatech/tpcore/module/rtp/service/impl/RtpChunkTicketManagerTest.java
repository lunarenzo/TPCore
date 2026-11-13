package com.lunatech.tpcore.module.rtp.service.impl;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class RtpChunkTicketManagerTest {
    @Test void anOldExpiryCannotReleaseANewLeaseAfterReload() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var scheduler = mock(RegionScheduler.class); var world = mock(World.class); var plugin = mock(JavaPlugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            var id = UUID.randomUUID(); when(world.getUID()).thenReturn(id); when(world.isChunkLoaded(0, 0)).thenReturn(true);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(scheduler); bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
            doAnswer(call -> { ((Runnable) call.getArgument(4)).run(); return null; })
                .when(scheduler).execute(any(), any(World.class), anyInt(), anyInt(), any());
            var expiries = new ArrayList<Consumer<ScheduledTask>>();
            when(scheduler.runDelayed(any(), any(World.class), anyInt(), anyInt(), any(), anyLong()))
                .thenAnswer(call -> { expiries.add(call.getArgument(4)); return mock(ScheduledTask.class); });
            var tickets = new RtpChunkTicketManager(plugin);
            tickets.addChunkTicket(world, 0, 0); tickets.clear(); tickets.addChunkTicket(world, 0, 0);
            verify(world).removePluginChunkTicket(0, 0, plugin);
            expiries.getFirst().accept(null);
            verify(world).removePluginChunkTicket(0, 0, plugin);
            expiries.getLast().accept(null);
            verify(world, times(2)).removePluginChunkTicket(0, 0, plugin);
            tickets.close();
        }
    }
}
