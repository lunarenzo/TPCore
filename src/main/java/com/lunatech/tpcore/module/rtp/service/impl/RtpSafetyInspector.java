package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.config.RtpWorldConfig;
import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import com.lunatech.tpcore.module.rtp.util.BiomeResolver;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public final class RtpSafetyInspector {

    private static final int[] PROBE_DX = {0, 1, -1, 0, 0, 2, -2, 2, -2, 4, -4};
    private static final int[] PROBE_DZ = {0, 0, 0, 1, -1, 2, -2, -2, 2, 4, -4};

    private final Plugin plugin;
    private final Semaphore inspections = new Semaphore(32);
    private final Set<UUID> dispatched = new HashSet<>();
    public synchronized boolean reserveDispatch(UUID player) { return dispatched.size() < 32 && dispatched.add(player); }
    public synchronized void releaseDispatch(UUID player) { dispatched.remove(player); }
    public synchronized boolean isDispatched(UUID player) { return dispatched.contains(player); }

    public RtpSafetyInspector(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    public CompletableFuture<RtpCandidate> inspectCandidate(World world, RtpWorldConfig worldConfig, int candidateX, int candidateZ) {
        return inspectCandidate(world, worldConfig, candidateX, candidateZ, true);
    }

    public CompletableFuture<RtpCandidate> inspectCandidate(World world, RtpWorldConfig worldConfig, int candidateX, int candidateZ, boolean allowGeneration) {
        Objects.requireNonNull(world, "world cannot be null");
        Objects.requireNonNull(worldConfig, "worldConfig cannot be null");

        int chunkX = candidateX >> 4;
        int chunkZ = candidateZ >> 4;

        if (!inspections.tryAcquire()) return CompletableFuture.completedFuture(null);
        var result = new CompletableFuture<RtpCandidate>();
        try {
            world.getChunkAtAsync(chunkX, chunkZ, allowGeneration).whenComplete((chunk, error) -> {
                if (error != null || chunk == null) { inspections.release(); result.complete(null); return; }
                try {
                    Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
                        try {
                            if (!world.isChunkLoaded(chunkX, chunkZ)) { result.complete(null); return; }
                            RtpCandidate candidate = switch (world.getEnvironment()) {
                                case NETHER -> inspectNether(world, worldConfig, chunk, candidateX, candidateZ, 0, 0);
                                case THE_END -> inspectEnd(world, worldConfig, chunk, candidateX, candidateZ, 0, 0);
                                default -> inspectOverworld(world, worldConfig, chunk, candidateX, candidateZ, 0, 0);
                            };
                            result.complete(candidate);
                        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
                        finally { inspections.release(); }
                    });
                } catch (RuntimeException retired) { inspections.release(); result.completeExceptionally(retired); }
            });
        } catch (RuntimeException rejected) { inspections.release(); result.completeExceptionally(rejected); }
        return result;
    }

    private RtpCandidate inspectOverworld(World world, RtpWorldConfig config, Chunk chunk, int targetX, int targetZ, int localX, int localZ) {
        for (int i = 0; i < PROBE_DX.length; i++) {
            int worldX = targetX + PROBE_DX[i];
            int worldZ = targetZ + PROBE_DZ[i];

            if ((worldX >> 4) != chunk.getX() || (worldZ >> 4) != chunk.getZ()) {
                continue;
            }

            if (!config.contains(worldX + 0.5, worldZ + 0.5)
                || !world.getWorldBorder().isInside(new Location(world, worldX + 0.5, 100, worldZ + 0.5))) continue;
            int lx = Math.floorMod(worldX, 16);
            int lz = Math.floorMod(worldZ, 16);

            int topY = world.getHighestBlockYAt(worldX, worldZ);
            int y = topY;
            while (y >= world.getMinHeight() && !isSolidGround(chunk.getBlock(lx, y, lz).getType())) {
                y--;
            }

            if (y < world.getMinHeight() || y >= world.getMaxHeight() - 2) {
                continue;
            }

            Material standOn = chunk.getBlock(lx, y, lz).getType();
            Material feet = chunk.getBlock(lx, y + 1, lz).getType();
            Material head = chunk.getBlock(lx, y + 2, lz).getType();

            if (standOn == Material.BEDROCK || isHazard(standOn) || !isPassable(feet) || !isPassable(head)) {
                continue;
            }

            String biomeKey = BiomeResolver.getBiomeKey(chunk.getBlock(lx, y + 1, lz));
            if (isBiomeBlacklisted(biomeKey, config)) {
                continue;
            }

            float yaw = ThreadLocalRandom.current().nextFloat() * 360.0f;
            float pitch = 0.0f;
            long packed = PackedLocation.pack(worldX, y + 1, worldZ);
            return PackedLocation.toCandidate(packed, world.getUID(), yaw, pitch);
        }
        return null;
    }

    private RtpCandidate inspectNether(World world, RtpWorldConfig config, Chunk chunk, int targetX, int targetZ, int localX, int localZ) {
        int startY = Math.min(125, world.getMaxHeight() - 3);
        int minScanY = Math.max(1, world.getMinHeight() + 5);

        for (int i = 0; i < PROBE_DX.length; i++) {
            int worldX = targetX + PROBE_DX[i];
            int worldZ = targetZ + PROBE_DZ[i];

            if ((worldX >> 4) != chunk.getX() || (worldZ >> 4) != chunk.getZ()) {
                continue;
            }

            if (!config.contains(worldX + 0.5, worldZ + 0.5)
                || !world.getWorldBorder().isInside(new Location(world, worldX + 0.5, 100, worldZ + 0.5))) continue;
            int lx = Math.floorMod(worldX, 16);
            int lz = Math.floorMod(worldZ, 16);

            for (int y = startY; y >= minScanY; y--) {
                Material standOn = chunk.getBlock(lx, y, lz).getType();
                Material feet = chunk.getBlock(lx, y + 1, lz).getType();
                Material head = chunk.getBlock(lx, y + 2, lz).getType();

                if (isSolidGround(standOn) && standOn != Material.BEDROCK && !isHazard(standOn) && isPassable(feet) && isPassable(head)) {
                    String biomeKey = BiomeResolver.getBiomeKey(chunk.getBlock(lx, y + 1, lz));
                    if (isBiomeBlacklisted(biomeKey, config)) {
                        break;
                    }
                    float yaw = ThreadLocalRandom.current().nextFloat() * 360.0f;
                    long packed = PackedLocation.pack(worldX, y + 1, worldZ);
                    return PackedLocation.toCandidate(packed, world.getUID(), yaw, 0.0f);
                }
            }
        }
        return null;
    }

    private RtpCandidate inspectEnd(World world, RtpWorldConfig config, Chunk chunk, int targetX, int targetZ, int localX, int localZ) {
        for (int i = 0; i < PROBE_DX.length; i++) {
            int worldX = targetX + PROBE_DX[i];
            int worldZ = targetZ + PROBE_DZ[i];

            if ((worldX >> 4) != chunk.getX() || (worldZ >> 4) != chunk.getZ()) {
                continue;
            }

            if (!config.contains(worldX + 0.5, worldZ + 0.5)
                || !world.getWorldBorder().isInside(new Location(world, worldX + 0.5, 100, worldZ + 0.5))) continue;
            int lx = Math.floorMod(worldX, 16);
            int lz = Math.floorMod(worldZ, 16);

            int topY = world.getHighestBlockYAt(worldX, worldZ);
            int y = topY;
            while (y >= 40 && !isSolidGround(chunk.getBlock(lx, y, lz).getType())) {
                y--;
            }

            if (y < 40 || y >= world.getMaxHeight() - 2) {
                continue;
            }

            Material standOn = chunk.getBlock(lx, y, lz).getType();
            Material feet = chunk.getBlock(lx, y + 1, lz).getType();
            Material head = chunk.getBlock(lx, y + 2, lz).getType();

            if (standOn == Material.BEDROCK || isHazard(standOn) || !isPassable(feet) || !isPassable(head)) {
                continue;
            }

            String biomeKey = BiomeResolver.getBiomeKey(chunk.getBlock(lx, y + 1, lz));
            if (isBiomeBlacklisted(biomeKey, config)) {
                continue;
            }

            float yaw = ThreadLocalRandom.current().nextFloat() * 360.0f;
            long packed = PackedLocation.pack(worldX, y + 1, worldZ);
            return PackedLocation.toCandidate(packed, world.getUID(), yaw, 0.0f);
        }
        return null;
    }

    private boolean isSolidGround(Material material) {
        if (material == null) return false;
        return material.isSolid() || material.name().endsWith("_LEAVES");
    }

    private boolean isHazard(Material material) {
        if (material == null || material.isAir()) return true;
        return material == Material.LAVA ||
               material == Material.WATER ||
               material == Material.FIRE ||
               material == Material.SOUL_FIRE ||
               material == Material.MAGMA_BLOCK ||
               material == Material.CACTUS ||
               material == Material.SWEET_BERRY_BUSH ||
               material == Material.WITHER_ROSE ||
               material == Material.POWDER_SNOW ||
               material == Material.VOID_AIR;
    }

    private boolean isPassable(Material material) {
        if (material == null || material.isAir()) return true;
        return !material.isSolid() && !isHazard(material);
    }

    private boolean isBiomeBlacklisted(String biomeKey, RtpWorldConfig config) {
        if (biomeKey == null || biomeKey.isEmpty()) return !config.biomeWhitelist().isEmpty();
        if (!config.biomeWhitelist().isEmpty() && config.biomeWhitelist().stream().noneMatch(value -> biomeKey.equalsIgnoreCase(value))) return true;
        for (String blacklisted : config.biomeBlacklist()) {
            if (biomeKey.contains(blacklisted.toUpperCase())) {
                return true;
            }
        }
        return false;
    }
}
