package com.lunatech.tpcore.module.warp.listener;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.service.WarpService;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

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

        if (event.getTo() == null) {
            return;
        }

        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        WarpService service = serviceSupplier.get();
        if (service != null) {
            service.cancelWarmupOnMove(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            return;
        }

        WarpService service = serviceSupplier.get();
        if (service != null) {
            service.cancelWarmupOnMove(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            return;
        }

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
            || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY
            || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT
            || cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            WarpService service = serviceSupplier.get();
            if (service != null) {
                service.cancelWarmupOnMove(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled() || event.getFinalDamage() <= 0) {
            return;
        }

        if (event.getEntity() instanceof Player player) {
            WarpService service = serviceSupplier.get();
            if (service != null) {
                Player attacker = null;
                if (event instanceof EntityDamageByEntityEvent hit) {
                    if (hit.getDamager() instanceof Player direct) attacker = direct;
                    else if (hit.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) attacker = shooter;
                }
                if (config.policies().combatEnabled() && (!config.policies().combatPvpOnly() || attacker != null)) {
                    service.recordCombat(player.getUniqueId());
                    if (attacker != null) service.recordCombat(attacker.getUniqueId());
                }
                service.cancelWarmupOnDamage(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        WarpService service = serviceSupplier.get();
        if (service != null) {
            service.cancelWarmupOnQuit(event.getPlayer().getUniqueId());
        }
    }
}
