package com.lunatech.tpcore.module.rtp.model;

import java.util.Objects;
import java.util.UUID;

public record RtpCandidate(
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    UUID worldUuid,
    long timestamp
) {
    public RtpCandidate {
        Objects.requireNonNull(worldUuid, "worldUuid cannot be null");
    }
}
