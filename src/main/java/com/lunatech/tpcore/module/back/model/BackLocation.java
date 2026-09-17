package com.lunatech.tpcore.module.back.model;

import java.util.UUID;

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
) {}
