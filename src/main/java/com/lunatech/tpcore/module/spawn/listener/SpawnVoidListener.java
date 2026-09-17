package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public final class SpawnVoidListener implements Listener {

    private final SpawnService spawnService;
    private final SpawnConfig config;

    public SpawnVoidListener(SpawnService spawnService, SpawnConfig config) {
        this.spawnService = spawnService;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!this.config.enabled() || !this.config.voidFallProtection()) {
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.VOID && event.getEntity() instanceof Player player) {
            event.setCancelled(true);
            this.spawnService.rescueFromVoid(player);
        }
    }
}
