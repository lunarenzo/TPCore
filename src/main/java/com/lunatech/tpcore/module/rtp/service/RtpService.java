package com.lunatech.tpcore.module.rtp.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * High-performance, zero-allocation service interface for Random Teleportation (RTP).
 */
public interface RtpService {

    /**
     * Executes random teleportation for a player targeting a specific world.
     *
     * @param player Target player
     * @param targetWorld Target world (or current world if null)
     * @return CompletableFuture completing with true if teleport succeeded, false otherwise
     */
    CompletableFuture<Boolean> executeRtp(Player player, World targetWorld);

    /**
     * Executes random teleportation for a player in their current world.
     *
     * @param player Target player
     * @return CompletableFuture completing with true if teleport succeeded, false otherwise
     */
    CompletableFuture<Boolean> executeRtp(Player player);

    /**
     * Gets remaining cooldown in seconds for a player.
     *
     * @param playerUniqueId Unique ID of player
     * @return Remaining cooldown seconds, or 0 if no active cooldown
     */
    long getRemainingCooldownSeconds(UUID playerUniqueId);

    /**
     * Checks whether a player is currently in warmup phase.
     *
     * @param playerUniqueId Unique ID of player
     * @return true if warming up
     */
    boolean isWarmingUp(UUID playerUniqueId);

    /**
     * Cancels any pending RTP warmup for a player.
     *
     * @param playerUniqueId Unique ID of player
     */
    void cancelWarmup(UUID playerUniqueId);

    /**
     * Triggers buffer prefetching and queue health replenishment for all active worlds.
     */
    void triggerReplenishment();

    /**
     * Cleanup resources and pending tasks on shutdown/reload.
     */
    void shutdown();
}
