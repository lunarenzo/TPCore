package com.lunatech.tpcore.module.rtp.listener;

import com.lunatech.tpcore.module.rtp.service.RtpService;
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
 * Predictive event listener that manages RTP buffer replenishment on player join
 * and cancels active warmups on movement, damage, death, world change, or disconnect.
 */
public final class RtpPredictiveJoinListener implements Listener {

    private final Supplier<RtpService> rtpServiceSupplier;

    public RtpPredictiveJoinListener(Supplier<RtpService> rtpServiceSupplier) {
        this.rtpServiceSupplier = Objects.requireNonNull(rtpServiceSupplier, "rtpServiceSupplier cannot be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        RtpService service = this.rtpServiceSupplier.get();
        if (service != null) {
            service.triggerReplenishment();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        RtpService service = this.rtpServiceSupplier.get();
        if (service != null) {
            service.cancelWarmup(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        RtpService service = this.rtpServiceSupplier.get();
        if (service != null && service.hasActiveWarmups() && service.isWarmingUp(event.getEntity().getUniqueId())) {
            service.cancelWarmup(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        RtpService service = this.rtpServiceSupplier.get();
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
            RtpService service = this.rtpServiceSupplier.get();
            if (service != null && service.hasActiveWarmups() && service.isWarmingUp(player.getUniqueId())) {
                service.cancelWarmup(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            RtpService service = this.rtpServiceSupplier.get();
            if (service != null && service.hasActiveWarmups() && service.isWarmingUp(player.getUniqueId())) {
                service.cancelWarmup(player.getUniqueId());
            }
        }
    }
}
