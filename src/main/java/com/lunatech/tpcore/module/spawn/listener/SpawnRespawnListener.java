package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Optional;

public final class SpawnRespawnListener implements Listener {

    private final SpawnService spawnService;
    private final SpawnConfig config;

    public SpawnRespawnListener(SpawnService spawnService, SpawnConfig config) {
        this.spawnService = spawnService;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!this.config.enabled() || !this.config.spawnOnRespawn()) {
            return;
        }

        if ((event.isBedSpawn() || event.isAnchorSpawn()) && !this.config.overrideBedRespawn()) {
            return;
        }

        Optional<Location> spawnLocOpt = this.spawnService.getEffectiveSpawnLocation(event.getPlayer().getWorld().getName());
        spawnLocOpt.ifPresent(event::setRespawnLocation);
    }
}
