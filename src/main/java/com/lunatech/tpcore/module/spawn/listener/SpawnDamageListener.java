package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
        if (!config.enabled() || !(event.getEntity() instanceof Player player)) {
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.VOID && config.voidFallProtection()) {
            event.setCancelled(true);
            this.spawnService.rescueFromVoid(player);
            return;
        }

        if (config.cancelOnDamage()) {
            this.spawnService.handlePlayerDamage(player.getUniqueId());
        }
    }
}
