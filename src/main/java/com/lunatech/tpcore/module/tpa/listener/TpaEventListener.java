package com.lunatech.tpcore.module.tpa.listener;

import com.lunatech.tpcore.module.tpa.service.TpaService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class TpaEventListener implements Listener {

    private final TpaService tpaService;

    public TpaEventListener(TpaService tpaService) {
        this.tpaService = tpaService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.tpaService.handlePlayerQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            this.tpaService.handlePlayerDamage(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        if (event.hasChangedBlock()) {
            this.tpaService.handlePlayerMove(event.getPlayer());
        }
    }
}
