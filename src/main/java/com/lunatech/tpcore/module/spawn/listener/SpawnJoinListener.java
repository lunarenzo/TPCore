package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

import java.util.Optional;

public final class SpawnJoinListener implements Listener {

    private final SpawnService spawnService;
    private final SpawnConfig config;

    public SpawnJoinListener(SpawnService spawnService, SpawnConfig config) {
        this.spawnService = spawnService;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerSpawnLocation(PlayerSpawnLocationEvent event) {
        if (!this.config.enabled()) {
            return;
        }

        Player player = event.getPlayer();
        boolean isFirstJoin = !player.hasPlayedBefore();
        boolean shouldTeleportToSpawn = (this.config.spawnOnFirstJoin() && isFirstJoin) || this.config.spawnOnJoin();

        if (shouldTeleportToSpawn) {
            Optional<Location> spawnLocOpt = this.spawnService.getEffectiveSpawnLocation(null);
            spawnLocOpt.ifPresent(event::setSpawnLocation);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        if (event.hasChangedBlock()) {
            this.spawnService.handlePlayerMove(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.spawnService.handlePlayerQuit(event.getPlayer().getUniqueId());
    }
}
