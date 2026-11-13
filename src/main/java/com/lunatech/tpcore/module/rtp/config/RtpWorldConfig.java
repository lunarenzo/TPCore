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
        // Older per-world entries omitted this previously unused setting.
        if (maxAttempts == 0) maxAttempts = 30;
        if (minRadius < 0 || maxRadius <= minRadius || maxRadius > 29_000_000
            || Math.abs((long) centerX) + maxRadius > 29_999_000 || Math.abs((long) centerZ) + maxRadius > 29_999_000
            || maxAttempts < 1 || maxAttempts > 100 || cooldownSeconds < 0 || warmupSeconds < 0 || warmupSeconds > 600
            || !Double.isFinite(cost) || cost < 0 || (!shape.equalsIgnoreCase("CIRCLE") && !shape.equalsIgnoreCase("SQUARE")))
            throw new IllegalArgumentException("Invalid RTP world policy: " + worldName);
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

    public boolean contains(double x, double z) {
        double dx = x - centerX, dz = z - centerZ;
        double distance = shapeEnum() == Shape.SQUARE ? Math.max(Math.abs(dx), Math.abs(dz)) : Math.hypot(dx, dz);
        return distance >= minRadius && distance <= maxRadius;
    }
}
