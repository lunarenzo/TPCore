package com.lunatech.tpcore.module.back.service.impl;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.Plugin;

public final class BackSafetyInspector {

    private static final int[] PROBE_DX = {0, 1, -1, 0, 0, 1, -1, 1, -1};
    private static final int[] PROBE_DZ = {0, 0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] PROBE_DY = {0, -1, 1, -2, 2};

    private static final Set<Material> HAZARD_MATERIALS = EnumSet.noneOf(Material.class);
    private static final boolean IS_FOLIA;
    private static final Method IS_OWNED_BY_CURRENT_REGION_CHUNK;
    private static final Method IS_OWNED_BY_CURRENT_REGION_LOC;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (Throwable ignored) {
            try {
                Bukkit.class.getMethod("getRegionScheduler");
                folia = true;
            } catch (Throwable ignored2) {
                folia = false;
            }
        }
        IS_FOLIA = folia;

        Method mChunk = null;
        Method mLoc = null;
        try {
            mChunk = Bukkit.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class);
        } catch (Throwable ignored) {}
        try {
            mLoc = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
        } catch (Throwable ignored) {}

        IS_OWNED_BY_CURRENT_REGION_CHUNK = mChunk;
        IS_OWNED_BY_CURRENT_REGION_LOC = mLoc;

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

    private BackSafetyInspector() {}

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

        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
            if (chunk == null) {
                future.complete(null);
                return;
            }

            if (IS_FOLIA) {
                Bukkit.getRegionScheduler().execute(plugin, targetLocation, () -> {
                    try {
                        Location safe = inspectLocation(targetLocation, autoAdjust, searchRadius, preventNetherRoof, maxNetherHeight);
                        future.complete(safe);
                    } catch (Throwable t) {
                        future.complete(null);
                    }
                });
            } else {
                try {
                    Location safe = inspectLocation(targetLocation, autoAdjust, searchRadius, preventNetherRoof, maxNetherHeight);
                    future.complete(safe);
                } catch (Throwable t) {
                    future.complete(null);
                }
            }
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

        Location probeLoc = (IS_OWNED_BY_CURRENT_REGION_LOC != null) ? targetLocation.clone() : null;

        // Enhanced Void Death Recovery: Probe horizontal columns to find nearest solid surface edge
        if (targetY < minWorldY) {
            for (int r = 0; r <= radius; r++) {
                for (int i = 0; i < PROBE_DX.length; i++) {
                    int checkX = targetX + (PROBE_DX[i] * r);
                    int checkZ = targetZ + (PROBE_DZ[i] * r);
                    int chunkX = checkX >> 4;
                    int chunkZ = checkZ >> 4;

                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }

                    if (probeLoc != null) {
                        probeLoc.setX(checkX);
                        probeLoc.setY(targetY);
                        probeLoc.setZ(checkZ);
                    }

                    boolean isOwned = (chunkX == originChunkX && chunkZ == originChunkZ)
                        || isRegionOwned(world, chunkX, chunkZ, probeLoc);

                    if (!isOwned) {
                        continue;
                    }

                    int surfaceY = findSurfaceY(world, checkX, checkZ, minWorldY, maxWorldY, preventNetherRoof, maxNetherHeight);
                    if (surfaceY >= minWorldY) {
                        Location candidate = new Location(world, checkX + 0.5, surfaceY + 1.0, checkZ + 0.5, targetLocation.getYaw(), targetLocation.getPitch());
                        if (isLocationSafe(candidate, preventNetherRoof, maxNetherHeight)) {
                            return candidate;
                        }
                    }
                }
            }
        }

        for (int r = 1; r <= radius; r++) {
            for (int i = 0; i < PROBE_DX.length; i++) {
                int checkX = targetX + (PROBE_DX[i] * r);
                int checkZ = targetZ + (PROBE_DZ[i] * r);

                int chunkX = checkX >> 4;
                int chunkZ = checkZ >> 4;

                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                if (probeLoc != null) {
                    probeLoc.setX(checkX);
                    probeLoc.setY(targetY);
                    probeLoc.setZ(checkZ);
                }

                boolean isOwned = (chunkX == originChunkX && chunkZ == originChunkZ)
                    || isRegionOwned(world, chunkX, chunkZ, probeLoc);

                if (!isOwned) {
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
            int topY = preventNetherRoof ? Math.min(maxWorldY, maxNetherHeight - 2) : maxWorldY;
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
            return -1;
        }
        int highest = world.getHighestBlockYAt(checkX, checkZ);
        if (highest >= minWorldY && highest <= maxWorldY) {
            return highest;
        }
        return -1;
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

        if (preventNetherRoof && world.getEnvironment() == World.Environment.NETHER && y >= maxNetherHeight) {
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

    private static boolean isRegionOwned(World world, int chunkX, int chunkZ, Location loc) {
        if (world == null) return false;
        if (!IS_FOLIA) return true;

        if (IS_OWNED_BY_CURRENT_REGION_CHUNK != null) {
            try {
                Boolean owned = (Boolean) IS_OWNED_BY_CURRENT_REGION_CHUNK.invoke(null, world, chunkX, chunkZ);
                if (owned != null) return owned;
            } catch (Throwable ignored) {}
        }
        if (IS_OWNED_BY_CURRENT_REGION_LOC != null && loc != null) {
            try {
                Boolean owned = (Boolean) IS_OWNED_BY_CURRENT_REGION_LOC.invoke(null, loc);
                if (owned != null) return owned;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
