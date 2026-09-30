package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.module.tpa.model.TpaType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;

import java.util.UUID;

/**
 * State record representing an ongoing teleport warmup countdown.
 */
final class ActiveWarmup {

    private final UUID teleportingPlayerId;
    private final UUID destinationPlayerId;
    private final String destinationPlayerName;
    private final String worldName;
    private final double startX;
    private final double startY;
    private final double startZ;
    private final int totalWarmupSeconds;
    private final BossBar bossBar;
    private final UUID senderId;
    private final TpaType requestType;
    private final double cost;
    private int remainingSeconds;
    private volatile boolean cancelled;
    private volatile ScheduledTask task;

    ActiveWarmup(
        UUID teleportingPlayerId,
        UUID destinationPlayerId,
        String destinationPlayerName,
        String worldName,
        double startX,
        double startY,
        double startZ,
        int totalWarmupSeconds,
        BossBar bossBar,
        UUID senderId,
        TpaType requestType,
        double cost
    ) {
        this.teleportingPlayerId = teleportingPlayerId;
        this.destinationPlayerId = destinationPlayerId;
        this.destinationPlayerName = destinationPlayerName;
        this.worldName = worldName;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.totalWarmupSeconds = totalWarmupSeconds;
        this.remainingSeconds = totalWarmupSeconds;
        this.bossBar = bossBar;
        this.senderId = senderId;
        this.requestType = requestType;
        this.cost = cost;
    }

    UUID teleportingPlayerId() {
        return teleportingPlayerId;
    }

    UUID destinationPlayerId() {
        return destinationPlayerId;
    }

    String destinationPlayerName() {
        return destinationPlayerName;
    }

    BossBar bossBar() {
        return bossBar;
    }

    UUID senderId() {
        return senderId;
    }

    TpaType requestType() {
        return requestType;
    }

    double cost() {
        return cost;
    }

    int remainingSeconds() {
        return remainingSeconds;
    }

    int totalWarmupSeconds() {
        return totalWarmupSeconds;
    }

    int decrementRemainingSeconds() {
        return --remainingSeconds;
    }

    boolean isCancelled() {
        return cancelled;
    }

    void markCancelled() {
        this.cancelled = true;
    }

    ScheduledTask task() {
        return task;
    }

    void setTask(ScheduledTask task) {
        this.task = task;
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
