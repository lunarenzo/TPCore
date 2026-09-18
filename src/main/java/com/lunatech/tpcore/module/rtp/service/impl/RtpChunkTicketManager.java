package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages 2-phase plugin chunk tickets for preloading candidate target locations.
 * Ensures zero-chunk-delay teleports by keeping a 3x3 inner-ring loaded before player arrival,
 * and immediately releasing tickets after teleportation.
 */
public final class RtpChunkTicketManager {

    private final JavaPlugin plugin;

    public RtpChunkTicketManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    /**
     * Adds plugin chunk tickets for a 3x3 region centered around the packed location's chunk.
     *
     * @param world Target world
     * @param packedLoc Target packed location
     */
    public void addCandidateTickets(World world, long packedLoc) {
        if (world == null) {
            return;
        }
        int blockX = PackedLocation.unpackX(packedLoc);
        int blockZ = PackedLocation.unpackZ(packedLoc);
        int centerChunkX = blockX >> 4;
        int centerChunkZ = blockZ >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.addPluginChunkTicket(centerChunkX + dx, centerChunkZ + dz, this.plugin);
            }
        }
    }

    /**
     * Removes plugin chunk tickets for a 3x3 region centered around the packed location's chunk.
     *
     * @param world Target world
     * @param packedLoc Target packed location
     */
    public void removeCandidateTickets(World world, long packedLoc) {
        if (world == null) {
            return;
        }
        int blockX = PackedLocation.unpackX(packedLoc);
        int blockZ = PackedLocation.unpackZ(packedLoc);
        int centerChunkX = blockX >> 4;
        int centerChunkZ = blockZ >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.removePluginChunkTicket(centerChunkX + dx, centerChunkZ + dz, this.plugin);
            }
        }
    }

    /**
     * Adds a single chunk plugin ticket.
     */
    public void addChunkTicket(World world, int chunkX, int chunkZ) {
        if (world != null) {
            world.addPluginChunkTicket(chunkX, chunkZ, this.plugin);
        }
    }

    /**
     * Removes a single chunk plugin ticket.
     */
    public void removeChunkTicket(World world, int chunkX, int chunkZ) {
        if (world != null) {
            world.removePluginChunkTicket(chunkX, chunkZ, this.plugin);
        }
    }
}
