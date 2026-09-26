package com.lunatech.tpcore.module.tpa.listener;

import com.lunatech.tpcore.module.tpa.service.TpaService;
import org.bukkit.GameMode;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
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
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        if (event != null && event.getPlayer() != null) {
            this.tpaService.handlePlayerQuit(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageProtection(EntityDamageEvent event) {
        if (event == null || event.isCancelled() || isExemptDamageCause(event.getCause())) {
            return;
        }

        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = null;
        boolean isPvp = false;

        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            Entity damager = byEntityEvent.getDamager();
            if (damager instanceof Player pDamager) {
                attacker = pDamager;
                isPvp = (victim != null);
            } else if (damager instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player pShooter) {
                long launchTime = System.currentTimeMillis() - (projectile.getTicksLived() * 50L);
                long protectionStart = this.tpaService.getTeleportProtectionStartTime(pShooter.getUniqueId());
                if (protectionStart == 0L || launchTime >= protectionStart) {
                    attacker = pShooter;
                }
                isPvp = (victim != null);
            } else if (damager instanceof AreaEffectCloud cloud
                    && cloud.getSource() instanceof Player pCloudShooter) {
                long launchTime = System.currentTimeMillis() - (cloud.getTicksLived() * 50L);
                long protectionStart = this.tpaService.getTeleportProtectionStartTime(pCloudShooter.getUniqueId());
                if (protectionStart == 0L || launchTime >= protectionStart) {
                    attacker = pCloudShooter;
                }
                isPvp = (victim != null);
            } else if (damager instanceof ThrownPotion potion
                    && potion.getShooter() instanceof Player pPotionShooter) {
                long launchTime = System.currentTimeMillis() - (potion.getTicksLived() * 50L);
                long protectionStart = this.tpaService.getTeleportProtectionStartTime(pPotionShooter.getUniqueId());
                if (protectionStart == 0L || launchTime >= protectionStart) {
                    attacker = pPotionShooter;
                }
                isPvp = (victim != null);
            }
        }

        if (victim == null && attacker == null) {
            return;
        }

        if (this.tpaService.handlePlayerProtectionDamage(victim, attacker, isPvp)) {
            event.setCancelled(true);
            if (victim != null && isCombustionDamage(event.getCause())) {
                victim.setFireTicks(0);
            }
        }
    }

    private boolean isCombustionDamage(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
    }

    private boolean isExemptDamageCause(EntityDamageEvent.DamageCause cause) {
        if (cause == null) {
            return false;
        }
        return cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.SUICIDE
                || cause == EntityDamageEvent.DamageCause.STARVATION
                || cause.name().equals("KILL");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            this.tpaService.handlePlayerDamage(victim.getUniqueId());
        }
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            Entity damager = byEntityEvent.getDamager();
            Player attacker = null;
            if (damager instanceof Player pDamager) {
                attacker = pDamager;
            } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player pShooter) {
                attacker = pShooter;
            } else if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player pCloudShooter) {
                attacker = pCloudShooter;
            } else if (damager instanceof ThrownPotion potion && potion.getShooter() instanceof Player pPotionShooter) {
                attacker = pPotionShooter;
            }
            if (attacker != null) {
                this.tpaService.handlePlayerDamage(attacker.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!this.tpaService.hasActiveWarmups() || event == null || event.getPlayer() == null || !event.hasChangedPosition()) {
            return;
        }
        this.tpaService.handlePlayerMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!this.tpaService.hasActiveWarmups() || event == null || event.getVehicle() == null || event.getTo() == null) {
            return;
        }
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                this.tpaService.handlePlayerMove(player, event.getTo());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRiptide(PlayerRiptideEvent event) {
        if (event != null && event.getPlayer() != null) {
            this.tpaService.handlePlayerTeleport(event.getPlayer().getUniqueId());
        }
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
        if (this.tpaService.hasActiveWarmups() && event != null && event.getEntered() instanceof Player player) {
            this.tpaService.handlePlayerMove(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (this.tpaService.hasActiveWarmups() && event != null && event.getExited() instanceof Player player) {
            this.tpaService.handlePlayerMove(player);
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
