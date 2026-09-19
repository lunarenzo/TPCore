package com.lunatech.tpcore.module.rtp.config;

import java.util.List;
import java.util.Objects;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record RtpWorldConfig(
    String worldName,
    boolean enabled,
    int minRadius,
    int maxRadius,
    int centerX,
    int centerZ,
    String shape,
    List<String> biomeBlacklist,
    List<String> biomeWhitelist,
    int maxAttempts,
    int cooldownSeconds,
    int warmupSeconds,
    double cost,
    boolean useChunkTickets,
    boolean allowMounted
) {
    public enum Shape {
        CIRCLE,
        SQUARE
    }

    public RtpWorldConfig {
        Objects.requireNonNull(worldName, "worldName cannot be null");
        shape = (shape != null && !shape.isBlank()) ? shape : "CIRCLE";
        biomeBlacklist = biomeBlacklist != null ? List.copyOf(biomeBlacklist) : List.of();
        biomeWhitelist = biomeWhitelist != null ? List.copyOf(biomeWhitelist) : List.of();
    }

    public RtpWorldConfig(
        String worldName,
        boolean enabled,
        int minRadius,
        int maxRadius,
        int centerX,
        int centerZ,
        String shape,
        List<String> biomeBlacklist,
        List<String> biomeWhitelist,
        int maxAttempts,
        int cooldownSeconds,
        int warmupSeconds,
        double cost,
        boolean useChunkTickets
    ) {
        this(worldName, enabled, minRadius, maxRadius, centerX, centerZ, shape, biomeBlacklist, biomeWhitelist, maxAttempts, cooldownSeconds, warmupSeconds, cost, useChunkTickets, false);
    }

    public Shape shapeEnum() {
        if ("SQUARE".equalsIgnoreCase(shape)) {
            return Shape.SQUARE;
        }
        return Shape.CIRCLE;
    }
}
