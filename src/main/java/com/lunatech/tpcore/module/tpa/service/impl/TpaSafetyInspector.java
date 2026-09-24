package com.lunatech.tpcore.module.tpa.service.impl;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class TpaSafetyInspector {

    private static final int[] PROBE_DX = {0, 1, -1, 0, 0, 1, -1, 1, -1};
    private static final int[] PROBE_DZ = {0, 0, 0, 1, -1, 1, 1, -1, -1};
    private static final int[] PROBE_DY = {0, -1, 1, -2, 2};

    private static final Set<Material> HAZARD_MATERIALS = EnumSet.noneOf(Material.class);
    private static final Method IS_OWNED_BY_CURRENT_REGION;

    static {
        Method m = null;
        try {
            m = Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
        } catch (Throwable ignored) {
        }
        IS_OWNED_BY_CURRENT_REGION = m;

        for (Material mat : Material.values()) {
            String name = mat.name();
            if (name.contains("LAVA") ||
                name.contains("FIRE") ||
                name.contains("CAMPFIRE") ||
                name.contains("MAGMA") ||
                name.contains("CACTUS") ||
                name.contains("BERRY_BUSH") ||
                name.contains("WITHER_ROSE") ||
                name.contains("POWDER_SNOW") ||
                name.startsWith("POINTED_DRIPSTONE") ||
                name.equals("RESPAWN_ANCHOR") ||
                name.contains("PORTAL") ||
                name.contains("GATEWAY") ||
                name.contains("VOID")) {
                HAZARD_MATERIALS.add(mat);
            }
        }
    }

    private TpaSafetyInspector() {}

    public static Location findSafeLocation(Location targetLocation) {
        if (targetLocation == null || targetLocation.getWorld() == null) {
            return null;
        }

        World world = targetLocation.getWorld();
        int targetX = targetLocation.getBlockX();
        int targetY = targetLocation.getBlockY();
        int targetZ = targetLocation.getBlockZ();

        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        boolean lastChunkLoaded = false;

        Location probeLoc = (IS_OWNED_BY_CURRENT_REGION != null) ? targetLocation.clone() : null;

        for (int i = 0; i < PROBE_DX.length; i++) {
            int checkX = targetX + PROBE_DX[i];
            int checkZ = targetZ + PROBE_DZ[i];

            int chunkX = checkX >> 4;
            int chunkZ = checkZ >> 4;

            if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
                lastChunkLoaded = world.isChunkLoaded(chunkX, chunkZ);
            }

            if (!lastChunkLoaded) {
                continue;
            }

            if (probeLoc != null) {
                try {
                    probeLoc.setX(checkX);
                    probeLoc.setY(targetY);
                    probeLoc.setZ(checkZ);
                    Boolean owned = (Boolean) IS_OWNED_BY_CURRENT_REGION.invoke(null, probeLoc);
                    if (owned != null && !owned) {
                        continue;
                    }
                } catch (Throwable ignored) {
                }
            }

            for (int dy : PROBE_DY) {
                int checkY = targetY + dy;
                if (checkY < world.getMinHeight() + 1 || checkY >= world.getMaxHeight() - 2) {
                    continue;
                }

                Block standOn = world.getBlockAt(checkX, checkY - 1, checkZ);
                Block feet = world.getBlockAt(checkX, checkY, checkZ);
                Block head = world.getBlockAt(checkX, checkY + 1, checkZ);
                Block overhead = world.getBlockAt(checkX, checkY + 2, checkZ);

                if (!isSolidGround(standOn) || !isPassable(feet) || !isPassable(head) || isHazard(overhead.getType()) || isFallingHazard(overhead.getType())) {
                    continue;
                }

                Location candidate = new Location(
                    world,
                    checkX + 0.5,
                    checkY,
                    checkZ + 0.5,
                    targetLocation.getYaw(),
                    targetLocation.getPitch()
                );
                if (world.getWorldBorder().isInside(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isFallingHazard(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.contains("SAND") ||
               name.contains("GRAVEL") ||
               name.contains("ANVIL") ||
               name.contains("LAVA") ||
               name.startsWith("POINTED_DRIPSTONE");
    }

    public static boolean isSolidGround(Block block) {
        if (block == null) {
            return false;
        }
        if (block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            return false;
        }
        if (block.getBlockData() instanceof Openable openable && openable.isOpen()) {
            return false;
        }
        return isSolidGround(block.getType());
    }

    public static boolean isSolidGround(Material material) {
        if (material == null || HAZARD_MATERIALS.contains(material)) {
            return false;
        }
        return material == Material.BEDROCK || material.isSolid();
    }

    public static boolean isSolidGroundName(String name) {
        if (name == null || name.isBlank() || isHazardName(name) || name.contains("AIR")) {
            return false;
        }
        return true;
    }

    public static boolean isHazard(Material material) {
        return material == null || HAZARD_MATERIALS.contains(material);
    }

    public static boolean isHazardName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("AIR") || upper.equals("CAVE_AIR")) {
            return false;
        }
        return upper.contains("LAVA") ||
               upper.contains("FIRE") ||
               upper.contains("CAMPFIRE") ||
               upper.contains("MAGMA") ||
               upper.contains("CACTUS") ||
               upper.contains("BERRY_BUSH") ||
               upper.contains("WITHER_ROSE") ||
               upper.contains("POWDER_SNOW") ||
               (upper.contains("DRIPSTONE") && !upper.contains("DRIPSTONE_BLOCK")) ||
               upper.contains("RESPAWN_ANCHOR") ||
               upper.contains("PORTAL") ||
               upper.contains("GATEWAY") ||
               upper.contains("VOID");
    }

    public static boolean isPassable(Block block) {
        if (block == null) {
            return true;
        }
        Material type = block.getType();
        if (type == Material.WATER || isHazard(type)) {
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

    public static boolean isPassable(Material material) {
        if (material == null) {
            return true;
        }
        if (material == Material.WATER || HAZARD_MATERIALS.contains(material)) {
            return false;
        }
        return !material.isSolid();
    }

    public static boolean isPassableName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        if (isHazardName(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("WATER")) {
            return false;
        }
        return upper.contains("AIR") || upper.contains("LIGHT") || upper.contains("GRASS") || upper.contains("FLOWER");
    }
}
