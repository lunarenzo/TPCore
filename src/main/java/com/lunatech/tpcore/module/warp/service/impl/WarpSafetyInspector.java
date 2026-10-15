package com.lunatech.tpcore.module.warp.service.impl;

import com.lunatech.tpcore.config.model.WarpConfig.WarpSafetyConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.Plugin;

/** Bounded search; every block read executes on the candidate's owning region. */
public final class WarpSafetyInspector {
    private WarpSafetyInspector() {}

    public record Offset(int x, int y, int z) {
        int distanceSquared() { return x * x + y * y + z * z; }
    }

    /** Built once per configuration, never per tick or per candidate. */
    public record Policy(WarpSafetyConfig config, Set<Material> hazards, List<Offset> offsets) {
        public Policy(WarpSafetyConfig config) { this(config, materials(config), candidates(config)); }
        private static Set<Material> materials(WarpSafetyConfig config) {
            Set<Material> result = EnumSet.noneOf(Material.class);
            for (Material material : Material.values()) {
                for (String pattern : config.hazardPatterns()) {
                    if (material.name().contains(pattern)) { result.add(material); break; }
                }
            }
            return Set.copyOf(result);
        }
        private static List<Offset> candidates(WarpSafetyConfig config) {
            int radius = config.destinationPolicy().equals("EXACT") ? 0 : config.maxAdjustmentDistance();
            List<Offset> values = new ArrayList<>();
            for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
                Offset offset = new Offset(x, y, z);
                if (offset.distanceSquared() <= radius * radius) values.add(offset);
            }
            values.sort(Comparator.comparingInt(Offset::distanceSquared).thenComparingInt(Offset::y).thenComparingInt(Offset::x).thenComparingInt(Offset::z));
            return List.copyOf(values.subList(0, Math.min(values.size(), config.maxCandidates())));
        }
    }

    public static CompletableFuture<Location> findSafeLocationAsync(Plugin plugin, Location target, Policy policy, BooleanSupplier active) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        if (target == null || target.getWorld() == null) result.complete(null);
        else probe(plugin, target, policy, active, 0, result);
        return result;
    }

    private static void probe(Plugin plugin, Location target, Policy policy, BooleanSupplier active, int index, CompletableFuture<Location> result) {
        if (result.isDone()) return;
        if (!active.getAsBoolean() || index >= policy.offsets().size()) { result.complete(null); return; }
        Offset offset = policy.offsets().get(index);
        Location candidate = new Location(target.getWorld(), target.getX() + offset.x(), target.getY() + offset.y(), target.getZ() + offset.z(), target.getYaw(), target.getPitch());
        World world = candidate.getWorld();
        try {
            world.getChunkAtAsync(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4).whenComplete((chunk, failure) -> {
                if (failure != null || chunk == null || !active.getAsBoolean()) { result.complete(null); return; }
                try {
                    Bukkit.getRegionScheduler().execute(plugin, candidate, () -> {
                        try {
                            if (!active.getAsBoolean()) { result.complete(null); return; }
                            if (isLocationSafe(candidate, policy)) result.complete(candidate);
                            else probe(plugin, target, policy, active, index + 1, result);
                        } catch (RuntimeException invalid) { result.completeExceptionally(invalid); }
                    });
                } catch (RuntimeException invalid) { result.completeExceptionally(invalid); }
            }).exceptionally(failure -> { result.completeExceptionally(failure); return null; });
        } catch (RuntimeException invalid) { result.completeExceptionally(invalid); }
    }

    public static CompletableFuture<Location> findSafeLocationAsync(Plugin plugin, Location target, boolean autoAdjust, int radius, boolean roof, int height) {
        return findSafeLocationAsync(plugin, target, new Policy(legacy(autoAdjust, radius, roof, height)), () -> true);
    }

    /** Synchronous compatibility entry point: never crosses into an unowned or unloaded chunk. */
    public static Location inspectLocation(Location target, boolean autoAdjust, int radius, boolean roof, int height) {
        if (target == null || target.getWorld() == null) return null;
        Policy policy = new Policy(legacy(autoAdjust, radius, roof, height));
        for (Offset offset : policy.offsets()) {
            Location candidate = new Location(target.getWorld(), target.getX() + offset.x(), target.getY() + offset.y(), target.getZ() + offset.z(), target.getYaw(), target.getPitch());
            int cx = candidate.getBlockX() >> 4, cz = candidate.getBlockZ() >> 4;
            if ((cx != (target.getBlockX() >> 4) || cz != (target.getBlockZ() >> 4)) && !Bukkit.isOwnedByCurrentRegion(target.getWorld(), cx, cz)) continue;
            if (target.getWorld().isChunkLoaded(cx, cz) && isLocationSafe(candidate, policy)) return candidate;
        }
        return null;
    }

    private static WarpSafetyConfig legacy(boolean adjust, int radius, boolean roof, int height) {
        WarpSafetyConfig defaults = WarpSafetyConfig.createDefault();
        return new WarpSafetyConfig(true, roof, height, adjust ? "NEARBY" : "EXACT", radius, defaults.maxCandidates(), defaults.waterPolicy(), defaults.hazardPatterns());
    }

    public static boolean isLocationSafe(Location location, boolean roof, int height) {
        return isLocationSafe(location, new Policy(legacy(false, 0, roof, height)));
    }

    public static boolean isLocationSafe(Location location, Policy policy) {
        if (location == null || location.getWorld() == null) return false;
        World world = location.getWorld();
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())) return false;
        if (world.getWorldBorder() != null && !world.getWorldBorder().isInside(location)) return false;
        double y = location.getY();
        if (y < world.getMinHeight() + 1 || y + 1.8 > world.getMaxHeight()) return false;
        if (policy.config().preventNetherRoof() && world.getEnvironment() == World.Environment.NETHER && y + 1.8 >= policy.config().maxNetherHeight()) return false;
        int x = location.getBlockX(), by = location.getBlockY(), z = location.getBlockZ();
        if (!solid(world.getBlockAt(x, by - 1, z), policy)) return false;
        // Conservative standing-player clearance, including fractional coordinates and head height.
        int minX = (int) Math.floor(location.getX() - 0.3), maxX = (int) Math.floor(Math.nextDown(location.getX() + 0.3));
        int minZ = (int) Math.floor(location.getZ() - 0.3), maxZ = (int) Math.floor(Math.nextDown(location.getZ() + 0.3));
        int top = (int) Math.floor(Math.nextDown(y + 1.8));
        for (int bx = minX; bx <= maxX; bx++) for (int bz = minZ; bz <= maxZ; bz++) {
            int cx = bx >> 4, cz = bz >> 4;
            if ((cx != (x >> 4) || cz != (z >> 4)) && (!Bukkit.isOwnedByCurrentRegion(world, cx, cz) || !world.isChunkLoaded(cx, cz))) return false;
            for (int bodyY = by; bodyY <= top; bodyY++) if (!passable(world.getBlockAt(bx, bodyY, bz), policy)) return false;
        }
        return true;
    }

    public static boolean isSolidGround(Block block) { return solid(block, new Policy(WarpSafetyConfig.createDefault())); }
    public static boolean isPassable(Block block) { return passable(block, new Policy(WarpSafetyConfig.createDefault())); }

    private static boolean solid(Block block, Policy policy) {
        if (block == null || policy.hazards().contains(block.getType())) return false;
        if (block.getBlockData() instanceof Waterlogged water && water.isWaterlogged()) return false;
        if (block.getBlockData() instanceof Openable open && open.isOpen()) return false;
        Material type = block.getType();
        if (type == Material.WATER) return !policy.config().waterPolicy().equals("DENY");
        if (block.isPassable()) return false;
        String name = type.name();
        if (name.contains("FENCE") || name.endsWith("_WALL")) return false;
        return type == Material.BEDROCK || type.isSolid();
    }

    private static boolean passable(Block block, Policy policy) {
        if (block == null || policy.hazards().contains(block.getType())) return false;
        if (block.getBlockData() instanceof Waterlogged water && water.isWaterlogged()) return false;
        if (block.getBlockData() instanceof Openable open && !open.isOpen()) return false;
        if (block.getType() == Material.WATER) return policy.config().waterPolicy().equals("ALLOW");
        return block.isPassable();
    }
}
