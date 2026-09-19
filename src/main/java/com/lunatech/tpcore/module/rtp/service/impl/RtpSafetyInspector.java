package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.anvil.AnvilPrefilter;
import com.lunatech.tpcore.module.rtp.config.RtpWorldConfig;
import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;

public final class RtpSafetyInspector {

    private static final int[] PROBE_DX = {0, 1, -1, 0, 0, 2, -2, 2, -2, 4, -4};
    private static final int[] PROBE_DZ = {0, 0, 0, 1, -1, 2, -2, -2, 2, 4, -4};

    private final Path dataFolder;

    public RtpSafetyInspector(Path dataFolder) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
    }

    public CompletableFuture<RtpCandidate> inspectCandidate(World world, RtpWorldConfig worldConfig, int candidateX, int candidateZ) {
        return inspectCandidate(world, worldConfig, candidateX, candidateZ, true);
    }

    public CompletableFuture<RtpCandidate> inspectCandidate(World world, RtpWorldConfig worldConfig, int candidateX, int candidateZ, boolean allowGeneration) {
        Objects.requireNonNull(world, "world cannot be null");
        Objects.requireNonNull(worldConfig, "worldConfig cannot be null");

        int chunkX = candidateX >> 4;
        int chunkZ = candidateZ >> 4;

        Set<String> unsafeMaterials = new HashSet<>(worldConfig.biomeBlacklist());

        String dimSubpath = "";
        if (world.getEnvironment() == World.Environment.NETHER) {
            dimSubpath = "DIM-1";
        } else if (world.getEnvironment() == World.Environment.THE_END) {
            dimSubpath = "DIM1";
        }

        Path worldFolder = world.getWorldFolder().toPath();
        AnvilPrefilter.Verdict verdict = AnvilPrefilter.probeSync(worldFolder, dimSubpath, chunkX, chunkZ, unsafeMaterials);
        if (verdict == AnvilPrefilter.Verdict.REJECT) {
            return CompletableFuture.completedFuture(null);
        }

        return world.getChunkAtAsync(chunkX, chunkZ, allowGeneration).thenApply(chunk -> {
            if (chunk == null) {
                return null;
            }

            if (!world.getWorldBorder().isInside(new Location(world, candidateX, 100, candidateZ))) {
                return null;
            }

            int localX = Math.floorMod(candidateX, 16);
            int localZ = Math.floorMod(candidateZ, 16);

            World.Environment env = world.getEnvironment();
            if (env == World.Environment.NETHER) {
                return inspectNether(world, worldConfig, chunk, candidateX, candidateZ, localX, localZ);
            } else if (env == World.Environment.THE_END) {
                return inspectEnd(world, worldConfig, chunk, candidateX, candidateZ, localX, localZ);
            } else {
                return inspectOverworld(world, worldConfig, chunk, candidateX, candidateZ, localX, localZ);
            }
        });
    }

    private RtpCandidate inspectOverworld(World world, RtpWorldConfig config, Chunk chunk, int targetX, int targetZ, int localX, int localZ) {
        for (int i = 0; i < PROBE_DX.length; i++) {
            int lx = Math.floorMod(localX + PROBE_DX[i], 16);
            int lz = Math.floorMod(localZ + PROBE_DZ[i], 16);
            int worldX = (chunk.getX() << 4) + lx;
            int worldZ = (chunk.getZ() << 4) + lz;

            int highestY = world.getHighestBlockYAt(worldX, worldZ);
            if (highestY < world.getMinHeight() || highestY >= world.getMaxHeight() - 2) {
                continue;
            }

            Material standOn = chunk.getBlock(lx, highestY, lz).getType();
            Material feet = chunk.getBlock(lx, highestY + 1, lz).getType();
            Material head = chunk.getBlock(lx, highestY + 2, lz).getType();

            if (!(standOn.isSolid() || standOn.name().endsWith("_LEAVES")) || standOn == Material.BEDROCK || isHazard(standOn) || !isPassable(feet) || !isPassable(head)) {
                continue;
            }

            Biome biome = chunk.getBlock(lx, highestY + 1, lz).getBiome();
            if (isBiomeBlacklisted(biome, config)) {
                continue;
            }

            float yaw = ThreadLocalRandom.current().nextFloat() * 360.0f;
            float pitch = 0.0f;
            long packed = PackedLocation.pack(worldX, highestY + 1, worldZ);
            return PackedLocation.toCandidate(packed, world.getUID(), yaw, pitch);
        }
        return null;
    }

    private RtpCandidate inspectNether(World world, RtpWorldConfig config, Chunk chunk, int targetX, int targetZ, int localX, int localZ) {
        for (int i = 0; i < PROBE_DX.length; i++) {
            int lx = Math.floorMod(localX + PROBE_DX[i], 16);
            int lz = Math.floorMod(localZ + PROBE_DZ[i], 16);
            int worldX = (chunk.getX() << 4) + lx;
            int worldZ = (chunk.getZ() << 4) + lz;

            for (int y = 110; y >= 32; y--) {
                Material standOn = chunk.getBlock(lx, y, lz).getType();
                Material feet = chunk.getBlock(lx, y + 1, lz).getType();
                Material head = chunk.getBlock(lx, y + 2, lz).getType();

                if (standOn.isSolid() && standOn != Material.BEDROCK && !isHazard(standOn) && isPassable(feet) && isPassable(head)) {
                    Biome biome = chunk.getBlock(lx, y + 1, lz).getBiome();
                    if (isBiomeBlacklisted(biome, config)) {
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
            int lx = Math.floorMod(localX + PROBE_DX[i], 16);
            int lz = Math.floorMod(localZ + PROBE_DZ[i], 16);
            int worldX = (chunk.getX() << 4) + lx;
            int worldZ = (chunk.getZ() << 4) + lz;

            int highestY = world.getHighestBlockYAt(worldX, worldZ);
            if (highestY < 40 || highestY >= world.getMaxHeight() - 2) {
                continue;
            }

            Material standOn = chunk.getBlock(lx, highestY, lz).getType();
            Material feet = chunk.getBlock(lx, highestY + 1, lz).getType();
            Material head = chunk.getBlock(lx, highestY + 2, lz).getType();

            if (!standOn.isSolid() || standOn == Material.BEDROCK || isHazard(standOn) || !isPassable(feet) || !isPassable(head)) {
                continue;
            }

            Biome biome = chunk.getBlock(lx, highestY + 1, lz).getBiome();
            if (isBiomeBlacklisted(biome, config)) {
                continue;
            }

            float yaw = ThreadLocalRandom.current().nextFloat() * 360.0f;
            long packed = PackedLocation.pack(worldX, highestY + 1, worldZ);
            return PackedLocation.toCandidate(packed, world.getUID(), yaw, 0.0f);
        }
        return null;
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

    private boolean isBiomeBlacklisted(Biome biome, RtpWorldConfig config) {
        if (biome == null) return false;
        String key = biome.name().toUpperCase();
        for (String blacklisted : config.biomeBlacklist()) {
            if (key.contains(blacklisted.toUpperCase())) {
                return true;
            }
        }
        return false;
    }
}
