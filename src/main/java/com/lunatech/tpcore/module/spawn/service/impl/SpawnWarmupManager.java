package com.lunatech.tpcore.module.spawn.service.impl;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SpawnWarmupManager {

    private final JavaPlugin plugin;
    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();

    private record ActiveWarmup(
        UUID playerId,
        String worldName,
        double startX,
        double startY,
        double startZ,
        ScheduledTask task
    ) {
        public boolean hasMoved(Location currentLoc) {
            if (currentLoc == null || currentLoc.getWorld() == null) {
                return true;
            }
            if (!this.worldName.equals(currentLoc.getWorld().getName())) {
                return true;
            }
            double dx = currentLoc.getX() - this.startX;
            double dy = currentLoc.getY() - this.startY;
            double dz = currentLoc.getZ() - this.startZ;
            return (dx * dx + dy * dy + dz * dz) > 0.25;
        }
    }

    public SpawnWarmupManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasActiveWarmup(UUID playerId) {
        return this.activeWarmups.containsKey(playerId);
    }

    public void startWarmup(Player player, int warmupSeconds, Runnable onComplete) {
        this.cancelWarmup(player.getUniqueId(), null);

        Location loc = player.getLocation();
        ScheduledTask task = player.getScheduler().runDelayed(
            this.plugin,
            scheduledTask -> {
                ActiveWarmup warmup = this.activeWarmups.remove(player.getUniqueId());
                if (warmup != null && player.isOnline()) {
                    onComplete.run();
                }
            },
            null,
            warmupSeconds * 20L
        );

        if (task != null) {
            this.activeWarmups.put(
                player.getUniqueId(),
                new ActiveWarmup(
                    player.getUniqueId(),
                    loc.getWorld().getName(),
                    loc.getX(),
                    loc.getY(),
                    loc.getZ(),
                    task
                )
            );
        }
    }

    public boolean checkMovement(Player player) {
        if (this.activeWarmups.isEmpty()) {
            return false;
        }
        ActiveWarmup warmup = this.activeWarmups.get(player.getUniqueId());
        return warmup != null && warmup.hasMoved(player.getLocation());
    }

    public void cancelWarmup(UUID playerId, Consumer<Player> onCancelled) {
        ActiveWarmup warmup = this.activeWarmups.remove(playerId);
        if (warmup != null) {
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
            if (onCancelled != null) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    onCancelled.accept(player);
                }
            }
        }
    }

    public void handleQuit(UUID playerId) {
        this.cancelWarmup(playerId, null);
    }

    public void cancelAll() {
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
        }
        this.activeWarmups.clear();
    }
}
