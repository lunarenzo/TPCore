package com.lunatech.tpcore.module.warp.listener;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class WarpEventListener implements Listener {

    private final Supplier<WarpService> serviceSupplier;
    private final Supplier<WarpConfig> configSupplier;

    public WarpEventListener(Supplier<WarpService> serviceSupplier, Supplier<WarpConfig> configSupplier) {
        this.serviceSupplier = Objects.requireNonNull(serviceSupplier, "serviceSupplier cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled() || !config.cancelOnMove()) {
            return;
        }

        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        WarpService service = serviceSupplier.get();
        if (service instanceof DefaultWarpService defaultService) {
            defaultService.cancelWarmupOnMove(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled() || !config.cancelOnDamage()) {
            return;
        }

        if (event.getEntity() instanceof Player player) {
            WarpService service = serviceSupplier.get();
            if (service instanceof DefaultWarpService defaultService) {
                defaultService.cancelWarmupOnDamage(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        WarpService service = serviceSupplier.get();
        if (service instanceof DefaultWarpService defaultService) {
            defaultService.cancelWarmupOnQuit(event.getPlayer().getUniqueId());
        }
    }
}
