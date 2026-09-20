package com.lunatech.tpcore.module.tpa.service.impl;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Locale;

public final class TpaSafetyInspector {

    private static final int[] PROBE_DX = {0, 1, -1, 0, 0, 1, -1, 1, -1};
    private static final int[] PROBE_DZ = {0, 0, 0, 1, -1, 1, 1, -1, -1};

    private TpaSafetyInspector() {}

    public static Location findSafeLocation(Location targetLocation) {
        if (targetLocation == null || targetLocation.getWorld() == null) {
            return null;
        }

        World world = targetLocation.getWorld();
        int targetX = targetLocation.getBlockX();
        int targetY = targetLocation.getBlockY();
        int targetZ = targetLocation.getBlockZ();

        for (int i = 0; i < PROBE_DX.length; i++) {
            int checkX = targetX + PROBE_DX[i];
            int checkZ = targetZ + PROBE_DZ[i];

            for (int dy = 0; dy <= 2; dy++) {
                int checkY = targetY + dy;
                if (checkY < world.getMinHeight() || checkY >= world.getMaxHeight() - 2) {
                    continue;
                }

                Block standOn = world.getBlockAt(checkX, checkY - 1, checkZ);
                Block feet = world.getBlockAt(checkX, checkY, checkZ);
                Block head = world.getBlockAt(checkX, checkY + 1, checkZ);

                if (isSolidGround(standOn.getType()) && isPassable(feet.getType()) && isPassable(head.getType())) {
                    return new Location(
                        world,
                        checkX + 0.5,
                        checkY,
                        checkZ + 0.5,
                        targetLocation.getYaw(),
                        targetLocation.getPitch()
                    );
                }
            }
        }
        return null;
    }

    public static boolean isSolidGround(Material material) {
        return material != null && isSolidGroundName(material.name());
    }

    public static boolean isSolidGroundName(String name) {
        if (name == null || name.isBlank() || isHazardName(name) || name.contains("AIR") || name.equals("BEDROCK")) {
            return false;
        }
        return true;
    }

    public static boolean isHazard(Material material) {
        return material == null || isHazardName(material.name());
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
               upper.contains("WATER") ||
               upper.contains("FIRE") ||
               upper.contains("MAGMA") ||
               upper.contains("CACTUS") ||
               upper.contains("BERRY_BUSH") ||
               upper.contains("WITHER_ROSE") ||
               upper.contains("POWDER_SNOW") ||
               upper.contains("VOID");
    }

    public static boolean isPassable(Material material) {
        return material == null || isPassableName(material.name());
    }

    public static boolean isPassableName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        if (isHazardName(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("AIR") || upper.contains("LIGHT") || upper.contains("GRASS") || upper.contains("FLOWER");
    }
}
