package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.function.Supplier;

public final class SpawnDamageListener implements Listener {

    private final SpawnService spawnService;
    private final Supplier<SpawnConfig> configSupplier;

    public SpawnDamageListener(SpawnService spawnService, Supplier<SpawnConfig> configSupplier) {
        this.spawnService = spawnService;
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        SpawnConfig config = this.configSupplier.get();
        if (!config.enabled()) {
            return;
        }

        Player victim = event.getEntity() instanceof Player p ? p : null;

        if (victim != null && event.getCause() == EntityDamageEvent.DamageCause.VOID && config.voidFallProtection()) {
            event.setCancelled(true);
            this.spawnService.rescueFromVoid(victim);
            return;
        }

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
                long protectionStart = this.spawnService.getTeleportProtectionStartTime(pShooter.getUniqueId());
                if (protectionStart == 0L || launchTime >= protectionStart) {
                    attacker = pShooter;
                }
                isPvp = (victim != null);
            } else if (damager instanceof AreaEffectCloud cloud
                    && cloud.getSource() instanceof Player pCloudShooter) {
                long launchTime = System.currentTimeMillis() - (cloud.getTicksLived() * 50L);
                long protectionStart = this.spawnService.getTeleportProtectionStartTime(pCloudShooter.getUniqueId());
                if (protectionStart == 0L || launchTime >= protectionStart) {
                    attacker = pCloudShooter;
                }
                isPvp = (victim != null);
            } else if (damager instanceof ThrownPotion potion
                    && potion.getShooter() instanceof Player pPotionShooter) {
                long launchTime = System.currentTimeMillis() - (potion.getTicksLived() * 50L);
                long protectionStart = this.spawnService.getTeleportProtectionStartTime(pPotionShooter.getUniqueId());
                if (protectionStart == 0L || launchTime >= protectionStart) {
                    attacker = pPotionShooter;
                }
                isPvp = (victim != null);
            } else if (damager instanceof TNTPrimed tnt
                    && tnt.getSource() instanceof Player pTntShooter) {
                long launchTime = System.currentTimeMillis() - (tnt.getTicksLived() * 50L);
                long protectionStart = this.spawnService.getTeleportProtectionStartTime(pTntShooter.getUniqueId());
                if (protectionStart == 0L || launchTime >= protectionStart) {
                    attacker = pTntShooter;
                }
                isPvp = (victim != null);
            }
        }

        if (victim != null || attacker != null) {
            if (this.spawnService.handlePlayerProtectionDamage(victim, attacker, isPvp)) {
                event.setCancelled(true);
                if (victim != null && isCombustionDamage(event.getCause())) {
                    victim.setFireTicks(0);
                }
                return;
            }
        }

        if (victim != null && config.cancelOnDamage() && this.spawnService.hasActiveWarmup(victim.getUniqueId())) {
            this.spawnService.handlePlayerDamage(victim.getUniqueId());
        }
    }

    private boolean isCombustionDamage(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
    }
}
