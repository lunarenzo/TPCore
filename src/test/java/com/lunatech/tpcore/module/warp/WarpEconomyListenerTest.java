package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.listener.WarpEconomyListener;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpEconomyListenerTest {
    @Test void lifecycleEventsCoalesceAndRefreshOnlyOnTheGlobalContext() {
        Plugin plugin = mock(Plugin.class); when(plugin.isEnabled()).thenReturn(true);
        Runnable refresh = mock(Runnable.class); WarpEconomyListener listener = new WarpEconomyListener(plugin, refresh);
        List<Runnable> scheduled = new ArrayList<>();
        try (var bukkit = mockStatic(Bukkit.class)) {
            GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class); bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            doAnswer(call -> { scheduled.add(call.getArgument(1)); return null; }).when(scheduler).execute(any(), any());
            listener.registered(mock(ServiceRegisterEvent.class)); listener.unregistered(mock(ServiceUnregisterEvent.class));
            assertEquals(1, scheduled.size()); verifyNoInteractions(refresh); scheduled.getFirst().run(); verify(refresh).run();
            listener.registered(mock(ServiceRegisterEvent.class)); when(plugin.isEnabled()).thenReturn(false);
            scheduled.get(1).run(); verifyNoMoreInteractions(refresh);
        }
    }
}
