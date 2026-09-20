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
    long visits,
    double averageRating,
    int totalRatings
) {
    public Pwarp(
        int id, UUID ownerUuid, String ownerName, String name, String description,
        String worldName, double x, double y, double z, float yaw, float pitch,
        String iconMaterial, String category, boolean isPrivate, long createdAt, long visits
    ) {
        this(id, ownerUuid, ownerName, name, description, worldName, x, y, z, yaw, pitch, iconMaterial, category, isPrivate, createdAt, visits, 0.0, 0);
    }

    public Pwarp {
        Objects.requireNonNull(ownerUuid, "ownerUuid cannot be null");
        Objects.requireNonNull(ownerName, "ownerName cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(worldName, "worldName cannot be null");
        iconMaterial = (iconMaterial != null && !iconMaterial.isBlank()) ? iconMaterial : "OAK_SIGN";
        category = (category != null && !category.isBlank()) ? category.toLowerCase(Locale.ROOT) : "general";
        description = (description != null) ? description : "";
        averageRating = Math.max(0.0, Math.min(5.0, averageRating));
        totalRatings = Math.max(0, totalRatings);
    }
}
