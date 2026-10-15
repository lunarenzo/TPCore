package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.service.WarpService;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpReloadHardeningTest {
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = WarpModule.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @Test void expiredQueuedReloadCannotApplyConfigurationLater() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); Object previous = field.get(null);
        try {
            var server = mock(Server.class); field.set(null, server);
            var global = mock(GlobalRegionScheduler.class); when(server.getGlobalRegionScheduler()).thenReturn(global);
            var queued = new CopyOnWriteArrayList<Runnable>();
            doAnswer(call -> { queued.add(call.getArgument(1)); return null; }).when(global).execute(any(), any());
            var plugin = mock(JavaPlugin.class); when(plugin.isEnabled()).thenReturn(true); when(plugin.getSLF4JLogger()).thenReturn(LoggerFactory.getLogger("reload"));
            var manager = mock(ModularConfigManager.class); WarpConfig original = WarpConfig.createDefault(), next = mock(WarpConfig.class);
            when(next.operationTimeoutSeconds()).thenReturn(1);
            when(manager.tryLoadModuleConfig(eq("warp"), eq(WarpConfig.class), any())).thenReturn(next);
            var module = new WarpModule(plugin, manager, original); var service = mock(WarpService.class);
            set(module, "service", service); set(module, "isInitialized", true);
            assertFalse(module.reloadConfig()); assertEquals(1, queued.size()); queued.getFirst().run();
            verify(service, never()).close(); verify(service, never()).updateConfig(any());
            Field config = WarpModule.class.getDeclaredField("config"); config.setAccessible(true); assertSame(original, config.get(module));
        } finally { field.set(null, previous); }
    }
    @Test void interruptedQueuedReloadCannotApplyLater() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); Object previous = field.get(null);
        try {
            var server = mock(Server.class); field.set(null, server);
            var global = mock(GlobalRegionScheduler.class); when(server.getGlobalRegionScheduler()).thenReturn(global);
            var queued = new CopyOnWriteArrayList<Runnable>();
            doAnswer(call -> { queued.add(call.getArgument(1)); return null; }).when(global).execute(any(), any());
            var plugin = mock(JavaPlugin.class); when(plugin.isEnabled()).thenReturn(true);
            var manager = mock(ModularConfigManager.class); when(manager.tryLoadModuleConfig(eq("warp"), eq(WarpConfig.class), any())).thenReturn(WarpConfig.createDefault());
            var module = new WarpModule(plugin, manager, WarpConfig.createDefault()); var service = mock(WarpService.class);
            set(module, "service", service); set(module, "isInitialized", true);
            Thread.currentThread().interrupt(); assertFalse(module.reloadConfig()); assertTrue(Thread.interrupted());
            queued.getFirst().run(); verify(service, never()).close(); verify(service, never()).updateConfig(any());
        } finally { Thread.interrupted(); field.set(null, previous); }
    }
    @Test void alreadyStartedReloadReportsItsActualOutcomeInsteadOfFalseFailure() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); Object previous = field.get(null);
        try {
            var server = mock(Server.class); field.set(null, server); var global = mock(GlobalRegionScheduler.class);
            when(server.getGlobalRegionScheduler()).thenReturn(global); var queued = new CopyOnWriteArrayList<Runnable>();
            doAnswer(call -> { queued.add(call.getArgument(1)); return null; }).when(global).execute(any(), any());
            var plugin = mock(JavaPlugin.class); when(plugin.isEnabled()).thenReturn(true); when(plugin.getSLF4JLogger()).thenReturn(LoggerFactory.getLogger("reload"));
            var manager = mock(ModularConfigManager.class); WarpConfig next = mock(WarpConfig.class); when(next.operationTimeoutSeconds()).thenReturn(1);
            when(manager.tryLoadModuleConfig(eq("warp"), eq(WarpConfig.class), any())).thenReturn(next);
            var module = new WarpModule(plugin, manager, WarpConfig.createDefault()); var service = mock(WarpService.class);
            CompletableFuture<Void> stopping = new CompletableFuture<>(); when(service.close()).thenReturn(stopping);
            set(module, "service", service); set(module, "isInitialized", true);
            var applied = CompletableFuture.supplyAsync(module::reloadConfig);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (queued.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(queued.isEmpty()); queued.getFirst().run(); Thread.sleep(1100);
            assertFalse(applied.isDone()); stopping.complete(null); assertTrue(applied.get(2, TimeUnit.SECONDS));
        } finally { field.set(null, previous); }
    }
}
