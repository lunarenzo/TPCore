package com.lunatech.tpcore.module.home.engine;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Limits work until the server actually finishes it, even after a caller stops waiting. */
public final class ConcurrentTeleportPipelineEngine {
    private final Plugin plugin;
    private final WorkBudget inspections = new WorkBudget("inspection");
    private final WorkBudget teleports = new WorkBudget("dispatch");
    private final int maxDispatches;
    private final ConcurrentHashMap<UUID, WorkTicket> dispatched = new ConcurrentHashMap<>();

    public ConcurrentTeleportPipelineEngine(Plugin plugin, int maxDispatches) {
        this.plugin = plugin;
        this.maxDispatches = maxDispatches;
    }

    public static World resolve(Home home, HomeConfig config) {
        return home.worldId() != null ? Bukkit.getWorld(home.worldId())
            : config.runtime().allowLegacyWorldNames() ? Bukkit.getWorld(home.worldName()) : null;
    }

    public static Location position(Home home, World world) {
        return new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
    }

    private record Probe(Location location, HomeConfig config, CompletableFuture<HomeResultStatus> result) {}

    private final class WorkBudget {
        private final String phase;
        private int active;
        private int overdue;

        WorkBudget(String phase) { this.phase = phase; }

        synchronized WorkTicket acquire(int limit, int reserve) {
            if (active >= limit || active + overdue >= limit + reserve) return null;
            active++;
            return new WorkTicket(this);
        }

        synchronized void abandon(WorkTicket ticket) {
            if (ticket.done || ticket.overdue) return;
            ticket.overdue = true;
            active--;
            overdue++;
            plugin.getSLF4JLogger().warn("Home {} no longer observed: {} active, {} overdue", phase, active, overdue);
        }

        synchronized void release(WorkTicket ticket) {
            if (ticket.done) return;
            ticket.done = true;
            if (ticket.overdue) {
                overdue--;
                plugin.getSLF4JLogger().info("Overdue Home {} finished: {} active, {} overdue", phase, active, overdue);
            } else active--;
        }
    }

    private static final class WorkTicket {
        private final WorkBudget budget;
        private boolean done;
        private boolean overdue;

        WorkTicket(WorkBudget budget) { this.budget = budget; }
        void abandon() { budget.abandon(this); }
        void release() { budget.release(this); }
    }

    public CompletableFuture<HomeResultStatus> inspect(Location location, HomeConfig config) {
        int limit = Math.clamp(config.safetyChecks().maxConcurrentChunkLoads(), 1, 64);
        WorkTicket ticket = inspections.acquire(limit, config.runtime().stalledWorkReserve());
        if (ticket == null) return CompletableFuture.completedFuture(HomeResultStatus.BUSY);
        var result = new CompletableFuture<HomeResultStatus>();
        var probe = new Probe(location, config, result);
        var observer = new WeakReference<>(probe);
        result.whenComplete((status, failure) -> {
            if (result.isCancelled()) ticket.abandon();
            Reference.reachabilityFence(probe);
        });
        try {
            Bukkit.getRegionScheduler().execute(plugin, location, () -> beginInspection(observer, ticket));
        } catch (RuntimeException failure) {
            ticket.release();
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void beginInspection(WeakReference<Probe> observer, WorkTicket ticket) {
        Probe probe = observer.get();
        if (probe == null || probe.result().isDone()) { ticket.release(); return; }
        Location target = probe.location();
        try {
            World world = target.getWorld();
            if (!withinBounds(target, probe.config())) {
                ticket.release();
                probe.result().complete(HomeResultStatus.UNSAFE_LOCATION);
                return;
            }
            world.getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4, probe.config().runtime().generateChunks())
                .whenComplete((chunk, error) -> {
                    Probe current = observer.get();
                    if (current == null || current.result().isDone()) { ticket.release(); return; }
                    if (error != null || chunk == null) {
                        ticket.release();
                        current.result().complete(HomeResultStatus.UNSAFE_LOCATION);
                        return;
                    }
                    try {
                        Bukkit.getRegionScheduler().execute(plugin, current.location(), () -> finishInspection(observer, ticket));
                    } catch (RuntimeException failure) {
                        ticket.release();
                        current.result().completeExceptionally(failure);
                    }
                });
        } catch (RuntimeException failure) {
            ticket.release();
            probe.result().completeExceptionally(failure);
        }
    }

    private void finishInspection(WeakReference<Probe> observer, WorkTicket ticket) {
        Probe probe = observer.get();
        try {
            if (probe != null && !probe.result().isDone()) {
                var status = isSafe(probe.location(), probe.config()) ? HomeResultStatus.SUCCESS : HomeResultStatus.UNSAFE_LOCATION;
                ticket.release();
                probe.result().complete(status);
            }
        } catch (RuntimeException failure) {
            ticket.release();
            probe.result().completeExceptionally(failure);
        } finally { ticket.release(); }
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

    public static boolean isSafe(Location target, HomeConfig config) {
        if (!withinBounds(target, config)) return false;
        if (!config.safetyChecks().preventUnsafeTeleport()) return true;
        World world = target.getWorld();
        double x = target.getX(), feet = target.getY(), z = target.getZ(), head = feet + 1.8;
        int minX = (int) Math.floor(x - .3), maxX = (int) Math.floor(Math.nextDown(x + .3));
        int minZ = (int) Math.floor(z - .3), maxZ = (int) Math.floor(Math.nextDown(z + .3));
        int top = (int) Math.floor(Math.nextDown(head));
        // Include shapes protruding above their block, such as fences and walls.
        int bottom = Math.max(world.getMinHeight(), target.getBlockY() - 2);
        boolean supported = false;
        for (int bx = minX; bx <= maxX; bx++) for (int bz = minZ; bz <= maxZ; bz++) {
            if (!Bukkit.isOwnedByCurrentRegion(world, bx >> 4, bz >> 4) || !world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            for (int by = bottom; by <= top; by++) {
                Block body = world.getBlockAt(bx, by, bz);
                boolean hazard = body.isLiquid() || hazardous(body, config);
                if (hazard && by + 1 > feet) return false;
                for (var box : body.getCollisionShape().getBoundingBoxes()) {
                    double left = bx + box.getMinX(), right = bx + box.getMaxX();
                    double front = bz + box.getMinZ(), back = bz + box.getMaxZ();
                    if (right <= x - .3 || left >= x + .3 || back <= z - .3 || front >= z + .3) continue;
                    double surface = by + box.getMaxY();
                    if (surface > feet && by + box.getMinY() < head) return false;
                    if (surface == feet) {
                        if (hazard) return false;
                        if (left <= x && x < right && front <= z && z < back) supported = true;
                    }
                }
            }
        }
        return supported;
    }

    private static boolean hazardous(Block block, HomeConfig config) {
        return config.runtime().hazards().contains(block.getType().name());
    }

    public boolean isDispatched(UUID player) { return dispatched.containsKey(player); }

    public boolean reserveDispatch(UUID player, int reserve) {
        WorkTicket ticket = teleports.acquire(maxDispatches, reserve);
        if (ticket == null) return false;
        if (dispatched.putIfAbsent(player, ticket) == null) return true;
        ticket.release();
        return false;
    }

    public void releaseDispatch(UUID player) {
        WorkTicket ticket = dispatched.remove(player);
        if (ticket != null) ticket.release();
    }

    public void teleport(Player player, Location target, UUID id, CompletableFuture<Boolean> result) {
        teleport(player, target, id, result, null);
    }

    public void teleport(Player player, Location target, UUID id, CompletableFuture<Boolean> result,
        BiConsumer<Boolean, Throwable> settlement) {
        WorkTicket ticket = dispatched.get(id);
        result.whenComplete((ignored, failure) -> { if (result.isCancelled()) ticket.abandon(); });
        var observer = new WeakReference<>(result);
        try {
            player.teleportAsync(target).whenComplete((success, failure) -> {
                if (dispatched.remove(id, ticket)) ticket.release();
                if (settlement != null) settlement.accept(success, failure);
                var current = observer.get();
                if (current != null) {
                    if (failure == null) current.complete(success);
                    else current.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            if (dispatched.remove(id, ticket)) ticket.release();
            if (settlement != null) settlement.accept(null, failure);
            result.completeExceptionally(failure);
        }
    }
}
