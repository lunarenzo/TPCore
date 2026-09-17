package com.lunatech.tpcore.module.back.model;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record BackLocation(
    UUID worldId,
    String worldName,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    long timestamp,
    BackCause cause
) {
    public BackLocation {
        Objects.requireNonNull(worldName, "worldName cannot be null");
        if (cause == null) {
            cause = BackCause.TELEPORT;
        }
    }

    public double distanceSquared(double otherX, double otherY, double otherZ) {
        double dx = this.x - otherX;
        double dy = this.y - otherY;
        double dz = this.z - otherZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean isSameWorld(UUID otherWorldId, String otherWorldName) {
        if (this.worldId != null && otherWorldId != null) {
            return this.worldId.equals(otherWorldId);
        }
        return this.worldName.equalsIgnoreCase(otherWorldName);
    }

    public Location toLocation(World world) {
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }
}

