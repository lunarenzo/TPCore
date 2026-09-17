package com.lunatech.tpcore.module.back.listener;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.service.BackService;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class BackEventListener implements Listener {

    private final Supplier<BackService> serviceSupplier;
    private final Supplier<BackConfig> configSupplier;

    public BackEventListener(Supplier<BackService> serviceSupplier, Supplier<BackConfig> configSupplier) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) return;

        if (event.getFrom() == null || event.getTo() == null) return;

        BackCause backCause = switch (event.getCause()) {
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> BackCause.PORTAL;
            default -> BackCause.TELEPORT;
        };


        BackService service = serviceSupplier.get();
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        BackConfig config = configSupplier.get();
        if (!config.enabled() || !config.cancelOnDamage()) return;

        if (event.getEntity() instanceof Player player) {
            BackService service = serviceSupplier.get();
            if (service != null) {
                service.cancelWarmupOnDamage(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        BackService service = serviceSupplier.get();
        if (service != null) {
            service.cancelWarmupOnQuit(event.getPlayer().getUniqueId());
        }
    }
}
