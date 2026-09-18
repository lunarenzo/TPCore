package com.lunatech.tpcore.module.rtp.config;

import java.util.List;
import java.util.Objects;

public record RtpWorldConfig(
    String worldName,
    boolean enabled,
    int minRadius,
    int maxRadius,
    int centerX,
    int centerZ,
    Shape shape,
    List<String> biomeBlacklist,
    List<String> biomeWhitelist,
    int maxAttempts,
    int cooldownSeconds,
    int warmupSeconds,
    double cost
) {
    public enum Shape {
        CIRCLE,
        SQUARE
    }

    public RtpWorldConfig {
        Objects.requireNonNull(worldName, "worldName cannot be null");
        Objects.requireNonNull(shape, "shape cannot be null");
        biomeBlacklist = biomeBlacklist != null ? List.copyOf(biomeBlacklist) : List.of();
        biomeWhitelist = biomeWhitelist != null ? List.copyOf(biomeWhitelist) : List.of();
    }
}
