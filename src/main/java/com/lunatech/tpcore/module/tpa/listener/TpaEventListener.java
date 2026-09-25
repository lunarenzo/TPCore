package com.lunatech.tpcore.module.tpa.listener;

import com.lunatech.tpcore.module.tpa.service.TpaService;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

public final class TpaEventListener implements Listener {

    private final TpaService tpaService;

    public TpaEventListener(TpaService tpaService) {
        this.tpaService = tpaService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event != null && event.getPlayer() != null) {
            this.tpaService.handlePlayerJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event != null && event.getPlayer() != null) {
            this.tpaService.handlePlayerQuit(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageProtection(EntityDamageEvent event) {
        if (event == null || !(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = null;
        boolean isPvp = false;
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            if (byEntityEvent.getDamager() instanceof Player pDamager) {
                attacker = pDamager;
                isPvp = true;
            } else if (byEntityEvent.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player pShooter) {
                attacker = pShooter;
                isPvp = true;
            }
        }

        if (this.tpaService.handlePlayerProtectionDamage(victim, attacker, isPvp)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event != null && event.getEntity() instanceof Player player) {
            this.tpaService.handlePlayerDamage(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event == null || event.getPlayer() == null || !event.hasChangedPosition()) {
            return;
        }
        this.tpaService.handlePlayerMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event != null && event.getPlayer() != null) {
            this.tpaService.handlePlayerTeleport(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event != null && event.getEntity() != null) {
            this.tpaService.handlePlayerDeath(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (event != null && event.getPlayer() != null) {
            this.tpaService.handlePlayerTeleport(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event != null && event.getEntered() instanceof Player player) {
            this.tpaService.handlePlayerTeleport(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        if (event != null && event.getPlayer() != null && event.getNewGameMode() == GameMode.SPECTATOR) {
            this.tpaService.handlePlayerTeleport(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (event != null && event.getPlayer() != null) {
            this.tpaService.handlePlayerTeleport(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        if (event != null && event.getEntity() instanceof Player player) {
            this.tpaService.handlePlayerTeleport(player.getUniqueId());
        }
    }
}
