package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.function.Supplier;

public final class SpawnJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final SpawnService spawnService;
    private final Supplier<SpawnConfig> configSupplier;

    public SpawnJoinListener(JavaPlugin plugin, SpawnService spawnService, Supplier<SpawnConfig> configSupplier) {
        this.plugin = plugin;
        this.spawnService = spawnService;
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        SpawnConfig config = this.configSupplier.get();
        if (!config.enabled()) {
            return;
        }

        Player player = event.getPlayer();
        boolean isFirstJoin = !player.hasPlayedBefore();
        boolean shouldTeleportToSpawn = (config.spawnOnFirstJoin() && isFirstJoin) || config.spawnOnJoin();

        if (shouldTeleportToSpawn) {
            player.getScheduler().run(this.plugin, task -> {
                if (player.isOnline()) {
                    Optional<Location> spawnLocOpt = this.spawnService.getEffectiveSpawnLocation(null);
                    spawnLocOpt.ifPresent(player::teleportAsync);
                }
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.spawnService.handlePlayerQuit(event.getPlayer().getUniqueId());
    }
}
