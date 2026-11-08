package com.lunatech.tpcore.module.home.engine;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Limits work until the server actually finishes it, even after a caller stops waiting. */
public final class ConcurrentTeleportPipelineEngine {
    private final Plugin plugin;
    private final AtomicInteger inspections = new AtomicInteger();
    private final Semaphore dispatchSlots;
    private final ConcurrentHashMap<UUID, UUID> dispatched = new ConcurrentHashMap<>();

    public ConcurrentTeleportPipelineEngine(Plugin plugin, int maxDispatches) {
        this.plugin = plugin;
        dispatchSlots = new Semaphore(maxDispatches);
    }

    public static World resolve(Home home, HomeConfig config) {
        return home.worldId() != null ? Bukkit.getWorld(home.worldId())
            : config.runtime().allowLegacyWorldNames() ? Bukkit.getWorld(home.worldName()) : null;
    }

    public static Location position(Home home, World world) {
        return new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
    }

    private record Probe(Location location, HomeConfig config, CompletableFuture<HomeResultStatus> result) {}

    public CompletableFuture<HomeResultStatus> inspect(Location location, HomeConfig config) {
        int limit = Math.clamp(config.safetyChecks().maxConcurrentChunkLoads(), 1, 64);
        int count;
        do {
            count = inspections.get();
            if (count >= limit) return CompletableFuture.completedFuture(HomeResultStatus.BUSY);
        } while (!inspections.compareAndSet(count, count + 1));
        var result = new CompletableFuture<HomeResultStatus>();
        var probe = new Probe(location, config, result);
        var observer = new WeakReference<>(probe);
        result.whenComplete((status, failure) -> Reference.reachabilityFence(probe));
        try {
            Bukkit.getRegionScheduler().execute(plugin, location, () -> beginInspection(observer));
        } catch (RuntimeException failure) {
            inspections.decrementAndGet();
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void beginInspection(WeakReference<Probe> observer) {
        Probe probe = observer.get();
        if (probe == null || probe.result().isDone()) { inspections.decrementAndGet(); return; }
        Location target = probe.location();
        try {
            World world = target.getWorld();
            if (!withinBounds(target, probe.config())) {
                inspections.decrementAndGet();
                probe.result().complete(HomeResultStatus.UNSAFE_LOCATION);
                return;
            }
            world.getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4, probe.config().runtime().generateChunks())
                .whenComplete((chunk, error) -> {
                    Probe current = observer.get();
                    if (current == null || current.result().isDone()) { inspections.decrementAndGet(); return; }
                    if (error != null || chunk == null) {
                        inspections.decrementAndGet();
                        current.result().complete(HomeResultStatus.UNSAFE_LOCATION);
                        return;
                    }
                    try {
                        Bukkit.getRegionScheduler().execute(plugin, current.location(), () -> finishInspection(observer));
                    } catch (RuntimeException failure) {
                        inspections.decrementAndGet();
                        current.result().completeExceptionally(failure);
                    }
                });
        } catch (RuntimeException failure) {
            inspections.decrementAndGet();
            probe.result().completeExceptionally(failure);
        }
    }

    private void finishInspection(WeakReference<Probe> observer) {
        try {
            Probe probe = observer.get();
            if (probe != null && !probe.result().isDone()) {
                try {
                    probe.result().complete(isSafe(probe.location(), probe.config())
                        ? HomeResultStatus.SUCCESS : HomeResultStatus.UNSAFE_LOCATION);
                } catch (RuntimeException failure) { probe.result().completeExceptionally(failure); }
            }
        } finally { inspections.decrementAndGet(); }
    }

    private static boolean withinBounds(Location target, HomeConfig config) {
        World world = target.getWorld();
        double y = target.getY();
        return Double.isFinite(target.getX()) && Double.isFinite(target.getZ()) && Double.isFinite(y)
            && Float.isFinite(target.getYaw()) && Float.isFinite(target.getPitch())
            && y >= world.getMinHeight() + 1 && y + 1.8 <= world.getMaxHeight()
            && (!config.safetyChecks().preventNetherRoof() || world.getEnvironment() != World.Environment.NETHER
                || y + 1.8 <= config.safetyChecks().maxNetherHeight())
            && world.getWorldBorder().isInside(target);
    }

    private static boolean isSafe(Location target, HomeConfig config) {
        if (!withinBounds(target, config)) return false;
        if (!config.safetyChecks().preventUnsafeTeleport()) return true;
        World world = target.getWorld();
        int x = target.getBlockX(), y = target.getBlockY(), z = target.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Block floor = world.getBlockAt(x, y - 1, z);
        if (floor.isPassable() || floor.isLiquid() || hazardous(floor, config)) return false;
        int minX = (int) Math.floor(target.getX() - .3), maxX = (int) Math.floor(target.getX() + .3);
        int minZ = (int) Math.floor(target.getZ() - .3), maxZ = (int) Math.floor(target.getZ() + .3);
        int top = (int) Math.floor(Math.nextDown(target.getY() + 1.8));
        for (int bx = minX; bx <= maxX; bx++) for (int bz = minZ; bz <= maxZ; bz++) {
            if ((bx >> 4) != (x >> 4) || (bz >> 4) != (z >> 4)) {
                if (!Bukkit.isOwnedByCurrentRegion(world, bx >> 4, bz >> 4) || !world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            }
            for (int by = y; by <= top; by++) {
                Block body = world.getBlockAt(bx, by, bz);
                if (!body.isPassable() || body.isLiquid() || hazardous(body, config)) return false;
            }
        }
        return true;
    }

    private static boolean hazardous(Block block, HomeConfig config) {
        return config.runtime().hazards().contains(block.getType().name());
    }

    public boolean isDispatched(UUID player) { return dispatched.containsKey(player); }

    public UUID reserveDispatch(UUID player) {
        if (!dispatchSlots.tryAcquire()) return null;
        UUID ticket = UUID.randomUUID();
        if (dispatched.putIfAbsent(player, ticket) == null) return ticket;
        dispatchSlots.release();
        return null;
    }

    public void releaseDispatch(UUID player, UUID ticket) {
        if (dispatched.remove(player, ticket)) dispatchSlots.release();
    }

    public void teleport(Player player, Location target, UUID id, UUID ticket, CompletableFuture<Boolean> result) {
        var observer = new WeakReference<>(result);
        try {
            player.teleportAsync(target).whenComplete((success, failure) -> {
                releaseDispatch(id, ticket);
                var current = observer.get();
                if (current != null) {
                    if (failure == null) current.complete(success);
                    else current.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            releaseDispatch(id, ticket);
            result.completeExceptionally(failure);
        }
    }
}
