package com.lunatech.tpcore.module.warp.model;

import java.util.Objects;
import java.util.UUID;

public record Warp(
    String name,
    UUID worldId,
    String worldName,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    UUID creatorUuid,
    String category,
    String passwordHash,
    boolean permissionGated,
    long createdAt
) {
    public Warp {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(worldName, "worldName cannot be null");
        category = (category == null || category.isBlank()) ? "general" : category.toLowerCase();
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public Warp withLocation(String newWorldName, UUID newWorldId, double newX, double newY, double newZ, float newYaw, float newPitch) {
        return new Warp(
            name,
            newWorldId,
            newWorldName,
            newX,
            newY,
            newZ,
            newYaw,
            newPitch,
            creatorUuid,
            category,
            passwordHash,
            permissionGated,
            createdAt
        );
    }
}
