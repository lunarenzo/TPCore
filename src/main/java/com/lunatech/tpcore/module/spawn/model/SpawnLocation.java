package com.lunatech.tpcore.module.spawn.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record SpawnLocation(
    String worldName,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
    public static SpawnLocation fromBukkit(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            throw new IllegalArgumentException("Location and world cannot be null");
        }
        return new SpawnLocation(
            loc.getWorld().getName(),
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            loc.getYaw(),
            loc.getPitch()
        );
    }

    public Location toBukkit() {
        World world = Bukkit.getWorld(this.worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, this.x, this.y, this.z, this.yaw, this.pitch);
    }
}
