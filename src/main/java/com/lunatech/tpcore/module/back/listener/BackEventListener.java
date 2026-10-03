package com.lunatech.tpcore.module.back.listener;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.service.BackService;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

public final class BackEventListener implements Listener {

    private final Plugin plugin;
    private final Supplier<BackService> serviceSupplier;
    private final Supplier<BackConfig> configSupplier;
    private final Set<UUID> respawningPlayers = ConcurrentHashMap.newKeySet();

    public BackEventListener(Supplier<BackService> serviceSupplier, Supplier<BackConfig> configSupplier) {
        this(null, serviceSupplier, configSupplier);
    }

    public BackEventListener(Plugin plugin, Supplier<BackService> serviceSupplier, Supplier<BackConfig> configSupplier) {
        this.plugin = plugin;
        this.serviceSupplier = Objects.requireNonNull(serviceSupplier, "serviceSupplier cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        BackService service = serviceSupplier.get();
        if (service != null) {
            service.loadPlayerHistoryAsync(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        respawningPlayers.add(uuid);
        if (this.plugin != null) {
            event.getPlayer().getScheduler().runDelayed(
                this.plugin,
                task -> respawningPlayers.remove(uuid),
                () -> respawningPlayers.remove(uuid),
                2L
            );
        } else {
            respawningPlayers.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) return;

        if (event.getFrom() == null || event.getTo() == null) return;

        UUID uuid = event.getPlayer().getUniqueId();
        if (respawningPlayers.contains(uuid)) {
            return;
        }

        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.DISMOUNT
            || cause == PlayerTeleportEvent.TeleportCause.EXIT_BED
            || cause == PlayerTeleportEvent.TeleportCause.SPECTATE
            || cause == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return;
        }

        BackService service = serviceSupplier.get();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
            || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            if (service != null && service.hasTeleportProtection(event.getPlayer().getUniqueId())) {
                service.stripTeleportProtection(event.getPlayer().getUniqueId());
            }
        }

        BackCause backCause = switch (cause) {
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> BackCause.PORTAL;
            default -> BackCause.TELEPORT;
        };

        if (service != null) {
            service.recordLocation(event.getPlayer(), event.getFrom(), backCause);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        BackConfig config = configSupplier.get();
        if (!config.enabled() || !config.trackDeaths()) return;

        Player player = event.getEntity();
        BackService service = serviceSupplier.get();
        if (service != null && player.getLocation() != null) {
            service.recordDeathLocation(player, player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        BackConfig config = configSupplier.get();
        if (!config.enabled() || !config.cancelOnMove()) return;

        if (event.getTo() == null) return;

        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        BackService service = serviceSupplier.get();
        if (service != null) {
            service.cancelWarmupOnMove(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) return;

        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player attacker = null;
        boolean isPvp = false;

        BackService service = serviceSupplier.get();

        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            Entity damager = byEntityEvent.getDamager();
            if (damager instanceof Player pDamager) {
                attacker = pDamager;
            } else if (damager instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player pShooter) {
                attacker = pShooter;
            } else if (damager instanceof AreaEffectCloud cloud
                    && cloud.getSource() instanceof Player pCloudShooter) {
                attacker = pCloudShooter;
            } else if (damager instanceof ThrownPotion potion
                    && potion.getShooter() instanceof Player pPotionShooter) {
                attacker = pPotionShooter;
            } else if (damager instanceof TNTPrimed tnt
                    && tnt.getSource() instanceof Player pTntShooter) {
                attacker = pTntShooter;
            } else if (damager instanceof LightningStrike lightning
                    && lightning.getCausingEntity() instanceof Player pLightning) {
                attacker = pLightning;
            }
            isPvp = (victim != null && attacker != null && !victim.getUniqueId().equals(attacker.getUniqueId()));
        }

        if (service != null && (victim != null || attacker != null)) {
            if (service.handlePlayerProtectionDamage(victim, attacker, isPvp)) {
                event.setCancelled(true);
                if (victim != null && isCombustionDamage(event.getCause())) {
                    victim.setFireTicks(0);
                }
                return;
            }
        }

        if (victim != null && config.cancelOnDamage()) {
            if (service != null) {
                service.cancelWarmupOnDamage(victim);
            }
        }
    }

    private boolean isCombustionDamage(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        respawningPlayers.remove(uuid);
        BackService service = serviceSupplier.get();
        if (service != null) {
            service.cancelWarmupOnQuit(uuid);
        }
    }
}
