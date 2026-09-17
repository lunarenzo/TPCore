package com.lunatech.tpcore.module.home.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Home(
    UUID ownerUuid,
    String name,
    String worldName,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    long createdAt,
    Set<UUID> sharedWith
) {
    public Home {
        Objects.requireNonNull(ownerUuid, "ownerUuid cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(worldName, "worldName cannot be null");
        sharedWith = sharedWith == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(sharedWith));
    }

    public boolean isSharedWith(UUID uuid) {
        return sharedWith.contains(uuid);
    }

    public Home withSharedWith(Set<UUID> newSharedWith) {
        return new Home(ownerUuid, name, worldName, x, y, z, yaw, pitch, createdAt, newSharedWith);
    }
}
