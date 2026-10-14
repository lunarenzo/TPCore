package com.lunatech.tpcore.module.warp.service.impl;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.Plugin;

public final class WarpSafetyInspector {

    private static final int[] PROBE_DX = {0, 1, -1, 0, 0, 1, -1, 1, -1};
    private static final int[] PROBE_DZ = {0, 0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] PROBE_DY = {0, -1, 1, -2, 2};

    private static final Set<Material> HAZARD_MATERIALS = EnumSet.noneOf(Material.class);
    static {
        for (Material mat : Material.values()) {
            String name = mat.name();
            if (name.contains("LAVA") || name.contains("FIRE") || name.contains("CAMPFIRE")
                || name.contains("MAGMA") || name.contains("CACTUS") || name.contains("BERRY_BUSH")
                || name.contains("WITHER_ROSE") || name.contains("POWDER_SNOW") || name.contains("WEB")
                || name.contains("BUBBLE") || name.startsWith("POINTED_DRIPSTONE")
                || name.equals("RESPAWN_ANCHOR") || name.contains("PORTAL")
                || name.contains("GATEWAY") || name.contains("VOID")) {
                HAZARD_MATERIALS.add(mat);
            }
        }
    }

    private WarpSafetyInspector() {}

    public static CompletableFuture<Location> findSafeLocationAsync(
        Plugin plugin,
        Location targetLocation,
        boolean autoAdjust,
        int searchRadius,
        boolean preventNetherRoof,
        int maxNetherHeight
    ) {
        if (targetLocation == null || targetLocation.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }

        World world = targetLocation.getWorld();
        int chunkX = targetLocation.getBlockX() >> 4;
        int chunkZ = targetLocation.getBlockZ() >> 4;
        CompletableFuture<Location> future = new CompletableFuture<>();

        CompletableFuture<Chunk> chunkFuture = world.getChunkAtAsync(chunkX, chunkZ);
        if (chunkFuture == null) {
            future.complete(null);
            return future;
        }

        chunkFuture.thenAccept(chunk -> {
            if (chunk == null) {
                future.complete(null);
                return;
            }

            Bukkit.getRegionScheduler().execute(plugin, targetLocation, () -> {
                try {
                    future.complete(inspectLocation(targetLocation, autoAdjust, searchRadius, preventNetherRoof, maxNetherHeight));
                } catch (Throwable failure) {
                    plugin.getSLF4JLogger().error("Warp destination inspection failed", failure);
                    future.complete(null);
                }
            });
        }).exceptionally(ex -> {
            future.complete(null);
            return null;
        });

        return future;
    }

    public static Location inspectLocation(
        Location targetLocation,
        boolean autoAdjust,
        int searchRadius,
        boolean preventNetherRoof,
        int maxNetherHeight
    ) {
        if (targetLocation == null || targetLocation.getWorld() == null) {
            return null;
        }

        World world = targetLocation.getWorld();
        if (isLocationSafe(targetLocation, preventNetherRoof, maxNetherHeight)) {
            return targetLocation;
        }

        if (!autoAdjust) {
            return null;
        }

        int radius = Math.max(1, Math.min(10, searchRadius));
        int targetX = targetLocation.getBlockX();
        int targetY = targetLocation.getBlockY();
        int targetZ = targetLocation.getBlockZ();
        int originChunkX = targetX >> 4;
        int originChunkZ = targetZ >> 4;

        if (!world.isChunkLoaded(originChunkX, originChunkZ)) {
            return null;
        }

        int minWorldY = world.getMinHeight() + 1;
        int maxWorldY = world.getMaxHeight() - 2;


        // Downward vertical scan bounded near target Y first
        int searchMinY = Math.max(minWorldY, targetY - 16);
        int searchMaxY = Math.min(maxWorldY, targetY + 4);
        int surfaceY = findSurfaceY(world, targetX, targetZ, searchMinY, searchMaxY, preventNetherRoof, maxNetherHeight);
        if (surfaceY != Integer.MIN_VALUE && surfaceY >= minWorldY) {
            Location candidate = new Location(world, targetLocation.getX(), surfaceY + 1.0, targetLocation.getZ(), targetLocation.getYaw(), targetLocation.getPitch());
            if (isLocationSafe(candidate, preventNetherRoof, maxNetherHeight)) {
                return candidate;
            }
        }

        // Horizontal radial scan
        for (int r = 1; r <= radius; r++) {
            for (int i = 0; i < PROBE_DX.length; i++) {
                int checkX = targetX + (PROBE_DX[i] * r);
                int checkZ = targetZ + (PROBE_DZ[i] * r);

                int chunkX = checkX >> 4;
                int chunkZ = checkZ >> 4;

                if ((chunkX != originChunkX || chunkZ != originChunkZ)
                    && !Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) continue;

                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                for (int dy : PROBE_DY) {
                    int checkY = targetY + dy;
                    if (checkY < minWorldY || checkY > maxWorldY) {
                        continue;
                    }

                    Location candidate = new Location(world, checkX + 0.5, checkY, checkZ + 0.5, targetLocation.getYaw(), targetLocation.getPitch());
                    if (isLocationSafe(candidate, preventNetherRoof, maxNetherHeight)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static int findSurfaceY(World world, int checkX, int checkZ, int minWorldY, int maxWorldY, boolean preventNetherRoof, int maxNetherHeight) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            int topY = preventNetherRoof ? Math.min(maxWorldY, maxNetherHeight - 3) : maxWorldY;
            for (int y = topY; y >= minWorldY; y--) {
                Block ground = world.getBlockAt(checkX, y, checkZ);
                if (isSolidGround(ground)) {
                    Block feet = world.getBlockAt(checkX, y + 1, checkZ);
                    Block head = world.getBlockAt(checkX, y + 2, checkZ);
                    if (isPassable(feet) && isPassable(head)) {
                        return y;
                    }
                }
            }
            return Integer.MIN_VALUE;
        }
        int highest = world.getHighestBlockYAt(checkX, checkZ);
        int startY = Math.min(maxWorldY, Math.max(minWorldY, highest));
        for (int y = startY; y >= minWorldY; y--) {
            Block ground = world.getBlockAt(checkX, y, checkZ);
            if (isSolidGround(ground)) {
                Block feet = world.getBlockAt(checkX, y + 1, checkZ);
                Block head = world.getBlockAt(checkX, y + 2, checkZ);
                if (isPassable(feet) && isPassable(head)) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    public static boolean isLocationSafe(Location location, boolean preventNetherRoof, int maxNetherHeight) {
        if (location == null) return false;
        World world = location.getWorld();
        if (world == null) return false;

        if (world.getWorldBorder() != null && !world.getWorldBorder().isInside(location)) {
            return false;
        }

        double y = location.getY();
        if (y < (world.getMinHeight() + 1) || y >= (world.getMaxHeight() - 1)) {
            return false;
        }

        if (preventNetherRoof && world.getEnvironment() == World.Environment.NETHER && (y + 1.0) >= maxNetherHeight) {
            return false;
        }

        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();

        Block feet = world.getBlockAt(blockX, blockY, blockZ);
        Block head = world.getBlockAt(blockX, blockY + 1, blockZ);
        Block ground = world.getBlockAt(blockX, blockY - 1, blockZ);

        if (!isSolidGround(ground)) {
            return false;
        }

        if (!isPassable(feet) || !isPassable(head)) {
            return false;
        }

        return true;
    }

    public static boolean isSolidGround(Block block) {
        if (block == null) return false;
        if (block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            return false;
        }
        if (block.getBlockData() instanceof Openable openable && openable.isOpen()) {
            return false;
        }
        Material material = block.getType();
        if (material == Material.WATER) {
            return true;
        }
        if (HAZARD_MATERIALS.contains(material)) {
            return false;
        }
        if (Tag.FENCES.isTagged(material) || Tag.WALLS.isTagged(material)) {
            return false;
        }
        String name = material.name();
        if (name.contains("FENCE") || (name.contains("WALL") && !name.contains("WALL_"))) {
            return false;
        }
        return material == Material.BEDROCK || material.isSolid();
    }

    public static boolean isPassable(Block block) {
        if (block == null) return true;
        Material type = block.getType();
        if (HAZARD_MATERIALS.contains(type) || isTriggerHazard(type)) {
            return false;
        }
        if (block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            return false;
        }
        if (block.getBlockData() instanceof Openable openable && !openable.isOpen()) {
            return false;
        }
        return block.isPassable();
    }

    private static boolean isTriggerHazard(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.contains("PRESSURE_PLATE")
            || name.contains("TRIPWIRE")
            || name.contains("SCULK_SENSOR")
            || name.contains("SCULK_SHRIEKER");
    }

}
