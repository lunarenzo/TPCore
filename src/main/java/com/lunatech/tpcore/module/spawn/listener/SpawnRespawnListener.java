package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Optional;
import java.util.function.Supplier;

public final class SpawnRespawnListener implements Listener {

    private final SpawnService spawnService;
    private final Supplier<SpawnConfig> configSupplier;

    public SpawnRespawnListener(SpawnService spawnService, Supplier<SpawnConfig> configSupplier) {
        this.spawnService = spawnService;
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        SpawnConfig config = this.configSupplier.get();
        if (!config.enabled() || !config.spawnOnRespawn()) {
            return;
        }

        if ((event.isBedSpawn() || event.isAnchorSpawn()) && !config.overrideBedRespawn()) {
            return;
        }

        Optional<Location> spawnLocOpt = this.spawnService.getEffectiveSpawnLocation(event.getPlayer().getWorld().getName());
        spawnLocOpt.ifPresent(event::setRespawnLocation);
    }
}
