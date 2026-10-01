package com.lunatech.tpcore.module.spawn.service.impl;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.module.spawn.economy.SpawnEconomyService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SpawnWarmupManager {

    private final JavaPlugin plugin;
    private final Supplier<SpawnConfig> configSupplier;
    private final SpawnEconomyService economyService;
    private final SpawnWarmupRenderer renderer;
    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();

    private static final class ActiveWarmup {
        private final UUID playerId;
        private final String worldName;
        private final double startX;
        private final double startY;
        private final double startZ;
        private final int totalSeconds;
        private int remainingSeconds;
        private final double paidCost;
        private BossBar bossBar;
        private ScheduledTask task;

        ActiveWarmup(UUID playerId, String worldName, double startX, double startY, double startZ, int totalSeconds, double paidCost) {
            this.playerId = playerId;
            this.worldName = worldName;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.totalSeconds = totalSeconds;
            this.remainingSeconds = totalSeconds;
            this.paidCost = paidCost;
        }

        boolean hasMoved(Location currentLoc) {
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

    public SpawnWarmupManager(JavaPlugin plugin, Supplier<SpawnConfig> configSupplier, SpawnEconomyService economyService, SpawnWarmupRenderer renderer) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.economyService = economyService;
        this.renderer = renderer;
    }

    public boolean hasActiveWarmup(UUID playerId) {
        return this.activeWarmups.containsKey(playerId);
    }

    public void startWarmup(Player player, int warmupSeconds, double paidCost, Runnable onComplete) {
        this.cancelWarmup(player.getUniqueId(), null);

        Location loc = player.getLocation();
        SpawnConfig cfg = this.configSupplier.get();
        ActiveWarmup warmup = new ActiveWarmup(
            player.getUniqueId(),
            loc.getWorld().getName(),
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            warmupSeconds,
            paidCost
        );

        BossBar bossBar = this.renderer.createBossBar(cfg, warmupSeconds);
        if (bossBar != null) {
            warmup.bossBar = bossBar;
            player.showBossBar(bossBar);
        }

        this.renderer.updateWarmupFeedback(player, cfg, warmupSeconds, warmupSeconds);

        ScheduledTask task = player.getScheduler().runAtFixedRate(
            this.plugin,
            scheduledTask -> {
                if (!player.isOnline()) {
                    this.cancelWarmup(player.getUniqueId(), null);
                    return;
                }

                warmup.remainingSeconds--;

                if (warmup.remainingSeconds <= 0) {
                    ActiveWarmup completed = this.activeWarmups.remove(player.getUniqueId());
                    if (completed != null) {
                        if (completed.task != null) {
                            completed.task.cancel();
                        }
                        if (completed.bossBar != null) {
                            player.hideBossBar(completed.bossBar);
                        }
                        if (cfg.enableSounds()) {
                            this.renderer.playSound(
                                player,
                                cfg.teleportSound(),
                                (float) cfg.teleportSoundVolume(),
                                (float) cfg.teleportSoundPitch()
                            );
                        }
                        onComplete.run();
                    }
                } else {
                    this.renderer.updateBossBar(warmup.bossBar, cfg, warmup.remainingSeconds, warmup.totalSeconds);
                    this.renderer.updateWarmupFeedback(player, cfg, warmup.remainingSeconds, warmup.totalSeconds);
                }
            },
            null,
            20L,
            20L
        );

        warmup.task = task;
        this.activeWarmups.put(player.getUniqueId(), warmup);
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
            if (warmup.task != null) {
                warmup.task.cancel();
            }
            Player player = Bukkit.getPlayer(playerId);
            SpawnConfig cfg = this.configSupplier.get();
            if (player != null && player.isOnline()) {
                if (warmup.bossBar != null) {
                    player.hideBossBar(warmup.bossBar);
                }
                if (cfg.enableSounds()) {
                    this.renderer.playSound(
                        player,
                        cfg.cancelSound(),
                        (float) cfg.cancelSoundVolume(),
                        (float) cfg.cancelSoundPitch()
                    );
                }
                if (onCancelled != null) {
                    onCancelled.accept(player);
                }
            }
            if (warmup.paidCost > 0.0 && cfg.refundOnCancel()) {
                this.economyService.processRefund(Bukkit.getOfflinePlayer(playerId), warmup.paidCost);
            }
        }
    }

    public void handleQuit(UUID playerId) {
        this.cancelWarmup(playerId, null);
    }

    public void cancelAll() {
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
            Player player = Bukkit.getPlayer(warmup.playerId);
            if (player != null && player.isOnline() && warmup.bossBar != null) {
                player.hideBossBar(warmup.bossBar);
            }
            if (warmup.paidCost > 0.0 && this.configSupplier.get().refundOnCancel()) {
                this.economyService.processRefund(Bukkit.getOfflinePlayer(warmup.playerId), warmup.paidCost);
            }
        }
        this.activeWarmups.clear();
        this.renderer.clear();
    }
}
