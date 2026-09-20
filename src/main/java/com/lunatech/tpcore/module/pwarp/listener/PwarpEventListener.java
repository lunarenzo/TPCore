package com.lunatech.tpcore.module.pwarp.listener;

import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Event listener that manages Pwarp session eviction on quit
 * and cancels active warmups on movement, damage, death, world change, or disconnect.
 */
public final class PwarpEventListener implements Listener {

    private final Supplier<PwarpService> pwarpServiceSupplier;

    public PwarpEventListener(Supplier<PwarpService> pwarpServiceSupplier) {
        this.pwarpServiceSupplier = Objects.requireNonNull(pwarpServiceSupplier, "pwarpServiceSupplier cannot be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        PwarpService service = this.pwarpServiceSupplier.get();
        if (service != null) {
            service.cancelWarmup(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        PwarpService service = this.pwarpServiceSupplier.get();
        if (service != null && service.hasActiveWarmups() && service.isWarmingUp(event.getEntity().getUniqueId())) {
            service.cancelWarmup(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        PwarpService service = this.pwarpServiceSupplier.get();
        if (service != null && service.hasActiveWarmups() && service.isWarmingUp(event.getPlayer().getUniqueId())) {
            service.cancelWarmup(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            Player player = event.getPlayer();
            PwarpService service = this.pwarpServiceSupplier.get();
            if (service != null && service.hasActiveWarmups() && service.isWarmingUp(player.getUniqueId())) {
                service.cancelWarmup(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            PwarpService service = this.pwarpServiceSupplier.get();
            if (service != null && service.hasActiveWarmups() && service.isWarmingUp(player.getUniqueId())) {
                service.cancelWarmup(player.getUniqueId());
            }
        }
    }
}
