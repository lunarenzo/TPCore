package com.lunatech.tpcore.module.warp.listener;

import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;

/** Coalesces service lifecycle notifications into one global metadata refresh. */
public final class WarpEconomyListener implements Listener {
    private final Plugin plugin;
    private final Runnable refresh;
    private final AtomicBoolean queued = new AtomicBoolean();
    public WarpEconomyListener(Plugin plugin, Runnable refresh) { this.plugin = plugin; this.refresh = refresh; }
    private void changed() {
        if (!plugin.isEnabled() || !queued.compareAndSet(false, true)) return;
        try { Bukkit.getGlobalRegionScheduler().execute(plugin, () -> { queued.set(false); if (plugin.isEnabled()) refresh.run(); }); }
        catch (RuntimeException failure) { queued.set(false); }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void registered(ServiceRegisterEvent event) { changed(); }
    @EventHandler(priority = EventPriority.MONITOR) public void unregistered(ServiceUnregisterEvent event) { changed(); }
    @EventHandler(priority = EventPriority.MONITOR) public void enabled(PluginEnableEvent event) { changed(); }
    @EventHandler(priority = EventPriority.MONITOR) public void disabled(PluginDisableEvent event) { changed(); }
}
