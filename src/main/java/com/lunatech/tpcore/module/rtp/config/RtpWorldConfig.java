package com.lunatech.tpcore.module.rtp.config;

import java.util.List;
import java.util.Locale;
import java.math.BigDecimal;
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
    String cost,
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
        biomeBlacklist = normalizeBiomes(biomeBlacklist);
        biomeWhitelist = normalizeBiomes(biomeWhitelist);
        // Older per-world entries omitted this previously unused setting.
        if (maxAttempts == 0) maxAttempts = 30;
        cost = cost == null ? "0.00" : cost;
        if (!cost.matches("[0-9]{1,12}(\\.[0-9]{1,8})?")) throw new IllegalArgumentException("Invalid RTP decimal price");
        if (minRadius < 0 || maxRadius <= minRadius || maxRadius > 29_000_000
            || Math.abs((long) centerX) + maxRadius > 29_999_000 || Math.abs((long) centerZ) + maxRadius > 29_999_000
            || maxAttempts < 1 || maxAttempts > 100 || cooldownSeconds < 0 || warmupSeconds < 0 || warmupSeconds > 600
            || (!shape.equalsIgnoreCase("CIRCLE") && !shape.equalsIgnoreCase("SQUARE")))
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
        this(worldName, enabled, minRadius, maxRadius, centerX, centerZ, shape, biomeBlacklist, biomeWhitelist, maxAttempts, cooldownSeconds, warmupSeconds, BigDecimal.valueOf(cost).toPlainString(), useChunkTickets, false);
    }

    public RtpWorldConfig(String worldName, boolean enabled, int minRadius, int maxRadius, int centerX, int centerZ,
        String shape, List<String> biomeBlacklist, List<String> biomeWhitelist, int maxAttempts, int cooldownSeconds,
        int warmupSeconds, double cost, boolean useChunkTickets, boolean allowMounted) {
        this(worldName, enabled, minRadius, maxRadius, centerX, centerZ, shape, biomeBlacklist, biomeWhitelist,
            maxAttempts, cooldownSeconds, warmupSeconds, BigDecimal.valueOf(cost).toPlainString(), useChunkTickets, allowMounted);
    }

    private static List<String> normalizeBiomes(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("MINECRAFT:")) normalized = normalized.substring(10);
            if (!normalized.matches("[A-Z0-9_]+")) throw new IllegalArgumentException("RTP biome names must be vanilla biome names");
            return normalized;
        }).distinct().toList();
    }
    public boolean allowsBiome(String biome) {
        if (biome == null || biome.isEmpty()) return biomeWhitelist.isEmpty() && biomeBlacklist.isEmpty();
        String fullKey = biome.toUpperCase(Locale.ROOT);
        String key = fullKey.startsWith("MINECRAFT:") ? fullKey.substring(10) : fullKey;
        return biomeBlacklist.stream().noneMatch(key::contains)
            && (biomeWhitelist.isEmpty() || biomeWhitelist.contains(key));
    }

    public BigDecimal price() { return new BigDecimal(cost); }

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
