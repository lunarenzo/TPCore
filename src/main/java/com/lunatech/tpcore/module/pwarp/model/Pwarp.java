package com.lunatech.tpcore.module.pwarp.model;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable data record representing a player-created warp location.
 */
public record Pwarp(
    int id,
    UUID ownerUuid,
    String ownerName,
    String name,
    String description,
    String worldName,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    String iconMaterial,
    String category,
    boolean isPrivate,
    long createdAt,
    long visits
) {
    public Pwarp {
        Objects.requireNonNull(ownerUuid, "ownerUuid cannot be null");
        Objects.requireNonNull(ownerName, "ownerName cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(worldName, "worldName cannot be null");
        iconMaterial = (iconMaterial != null && !iconMaterial.isBlank()) ? iconMaterial : "OAK_SIGN";
        category = (category != null && !category.isBlank()) ? category.toLowerCase(Locale.ROOT) : "general";
        description = (description != null) ? description : "";
    }
}
