package com.lunatech.tpcore.module.spawn.listener;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.function.Supplier;

public final class SpawnMoveListener implements Listener {

    private final SpawnService spawnService;
    private final Supplier<SpawnConfig> configSupplier;

    public SpawnMoveListener(SpawnService spawnService, Supplier<SpawnConfig> configSupplier) {
        this.spawnService = spawnService;
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        SpawnConfig config = this.configSupplier.get();
        if (!config.enabled() || !config.cancelOnMove()) {
            return;
        }

        if (event.hasChangedPosition()) {
            this.spawnService.handlePlayerMove(event.getPlayer());
        }
    }
}
