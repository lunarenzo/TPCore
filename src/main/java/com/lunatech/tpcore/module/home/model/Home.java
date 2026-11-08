package com.lunatech.tpcore.module.home.model;

import java.util.Objects;
import java.util.UUID;

public record Home(UUID ownerUuid, String name, String worldName, UUID worldId,
    double x, double y, double z, float yaw, float pitch, long createdAt) {
    public Home {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(worldName, "worldName");
    }
}
