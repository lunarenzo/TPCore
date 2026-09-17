package com.lunatech.tpcore.module.home.engine;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.model.Home;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class ConcurrentTeleportPipelineEngine {

    private final Plugin plugin;
    private final Supplier<HomeConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<ChunkKey, CompletableFuture<Chunk>> inFlightChunkLoads = new ConcurrentHashMap<>();
    private final Queue<TeleportTask> taskQueue = new ConcurrentLinkedQueue<>();
    private Object batchTask;

    public record ChunkKey(String worldName, int chunkX, int chunkZ) {}

    public record TeleportTask(
        Player player,
        Location target,
        Home home,
        CompletableFuture<Boolean> future,
        long requestedAt
    ) {}

    public record BenchmarkResult(
        int totalTasks,
        String targetWorlds,
        int uniqueChunkReads,
        double dedupRatio,
        int batchCap,
        int totalBatches,
        long totalTimeMs,
        double mspt,
        int successCount,
        int failCount
    ) {}

    public ConcurrentTeleportPipelineEngine(Plugin plugin, Supplier<HomeConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");

        startBatchProcessor();
    }

    public CompletableFuture<BenchmarkResult> runBenchmark(Player player, int taskCount, String targetWorldFilter) {
        Objects.requireNonNull(player, "player cannot be null");
        int count = Math.max(1, Math.min(1000, taskCount));
        long startTime = System.nanoTime();

        HomeConfig config = configSupplier.get();
        int maxLoadsPerTick = Math.max(1, config.safetyChecks().maxConcurrentChunkLoads());

        List<World> availableWorlds = Bukkit.getWorlds();
        List<World> targetWorlds;
        String filter = (targetWorldFilter != null && !targetWorldFilter.isBlank()) ? targetWorldFilter.trim() : "all";

        if ("all".equalsIgnoreCase(filter) || "*".equals(filter) || "any".equalsIgnoreCase(filter)) {
            targetWorlds = new ArrayList<>(availableWorlds);
        } else {
            targetWorlds = new ArrayList<>();
            String[] split = filter.split(",");
            for (String wName : split) {
                World w = Bukkit.getWorld(wName.trim());
                if (w != null) {
                    targetWorlds.add(w);
                }
            }
            if (targetWorlds.isEmpty()) {
                targetWorlds.add(player.getWorld());
            }
        }

        String worldsDisplay = targetWorlds.stream().map(World::getName).collect(Collectors.joining(", "));

        int uniqueChunksCount = Math.max(1, count / 4);
        AtomicInteger uniqueChunkReadsCounter = new AtomicInteger(0);
        AtomicInteger successCounter = new AtomicInteger(0);
        AtomicInteger failCounter = new AtomicInteger(0);

        List<CompletableFuture<Void>> taskFutures = new ArrayList<>(count);
        Location playerLoc = player.getLocation();

        for (int i = 0; i < count; i++) {
            World world = targetWorlds.get(i % targetWorlds.size());
            int chunkOffset = i % uniqueChunksCount;

            double targetY;
            if (i % 20 == 19 && world.getEnvironment() == World.Environment.NETHER) {
                targetY = config.safetyChecks().maxNetherHeight() + 5;
            } else {
                targetY = Math.min(Math.max(playerLoc.getY(), world.getMinHeight() + 5), world.getMaxHeight() - 5);
            }

            Location target = new Location(
                world,
                playerLoc.getBlockX() + (chunkOffset * 16) + 8,
                targetY,
                playerLoc.getBlockZ() + 8
            );

            if (!isPreFlightSafe(target, config)) {
                failCounter.incrementAndGet();
                continue;
            }

            int chunkX = target.getBlockX() >> 4;
            int chunkZ = target.getBlockZ() >> 4;
            ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);

            CompletableFuture<Chunk> chunkFuture = inFlightChunkLoads.computeIfAbsent(key, k -> {
                uniqueChunkReadsCounter.incrementAndGet();
                CompletableFuture<Chunk> cf = world.getChunkAtAsync(target);
                cf.whenComplete((c, ex) -> inFlightChunkLoads.remove(k));
                return cf;
            });

            CompletableFuture<Void> taskFuture = chunkFuture.thenAccept(chunk -> {
                addTicket(chunk);
                if (isLocationSafe(target)) {
                    successCounter.incrementAndGet();
                } else {
                    failCounter.incrementAndGet();
                }
                removeTicket(chunk);
            }).exceptionally(ex -> {
                failCounter.incrementAndGet();
                return null;
            });

            taskFutures.add(taskFuture);
        }

        int uniqueChunkReads = uniqueChunkReadsCounter.get();
        double dedupRatio = count > 0 ? (1.0 - ((double) uniqueChunkReads / count)) * 100.0 : 0.0;
        int totalBatches = (int) Math.ceil((double) count / maxLoadsPerTick);

        CompletableFuture<Void> allDone = CompletableFuture.allOf(taskFutures.toArray(new CompletableFuture[0]));

        return allDone.thenApply(v -> {
            long totalNano = System.nanoTime() - startTime;
            double mspt = totalNano / 1_000_000.0;
            long totalTimeMs = Math.round(mspt);

            return new BenchmarkResult(
                count,
                worldsDisplay,
                uniqueChunkReads,
                dedupRatio,
                maxLoadsPerTick,
                totalBatches,
                totalTimeMs,
                mspt,
                successCounter.get(),
                failCounter.get()
            );
        });
    }

    public boolean isPreFlightSafe(Location target, HomeConfig config) {
        if (target == null || target.getWorld() == null) {
            return false;
        }

        World world = target.getWorld();
        if (isWorldRestricted(world.getName(), config)) {
            return false;
        }

        if (target.getY() < world.getMinHeight() || target.getY() >= world.getMaxHeight()) {
            return false;
        }

        HomeConfig.HomeSafetyConfig safety = config.safetyChecks();
        if (safety.preventNetherRoof() && world.getEnvironment() == World.Environment.NETHER) {
            if (target.getY() > safety.maxNetherHeight()) {
                return false;
            }
        }

        return true;
    }

    public CompletableFuture<Boolean> submitTeleport(Player player, Home home, Location target) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(home, "home cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        HomeConfig config = configSupplier.get();

        if (!isPreFlightSafe(target, config)) {
            String msg = config.messages().prefix() + config.messages().unsafeLocation();
            player.sendMessage(miniMessage.deserialize(msg));
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        TeleportTask task = new TeleportTask(player, target, home, future, System.currentTimeMillis());
        taskQueue.offer(task);

        return future;
    }

    public boolean isLocationSafe(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        World world = location.getWorld();
        if (location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight()) {
            return false;
        }

        HomeConfig config = configSupplier.get();
        HomeConfig.HomeSafetyConfig safety = config.safetyChecks();

        if (safety.preventNetherRoof() && world.getEnvironment() == World.Environment.NETHER) {
            if (location.getY() > safety.maxNetherHeight()) {
                return false;
            }
        }

        if (!safety.preventUnsafeTeleport()) {
            return true;
        }

        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);

        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid() && !ground.isPassable() && !ground.isLiquid();
    }

    public void close() {
        if (batchTask != null) {
            cancelScheduledTask(batchTask);
            batchTask = null;
        }
        taskQueue.forEach(task -> task.future().complete(false));
        taskQueue.clear();
        inFlightChunkLoads.clear();
    }

    private void startBatchProcessor() {
        Runnable batchRunnable = this::processBatch;
        try {
            batchTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> batchRunnable.run(), 1L, 1L);
        } catch (NoSuchMethodError | Exception e) {
            int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, batchRunnable, 1L, 1L);
            batchTask = Integer.valueOf(taskId);
        }
    }

    private void processBatch() {
        if (taskQueue.isEmpty()) {
            return;
        }

        HomeConfig config = configSupplier.get();
        int maxLoadsPerTick = Math.max(1, config.safetyChecks().maxConcurrentChunkLoads());
        int processed = 0;
        long now = System.currentTimeMillis();

        while (processed < maxLoadsPerTick && !taskQueue.isEmpty()) {
            TeleportTask task = taskQueue.poll();
            if (task == null) {
                break;
            }

            Player player = task.player();
            if (!player.isOnline()) {
                task.future().complete(false);
                continue;
            }

            if (now - task.requestedAt() > 15000L) {
                task.future().complete(false);
                continue;
            }

            processed++;
            Location target = task.target();
            World world = target.getWorld();
            int chunkX = target.getBlockX() >> 4;
            int chunkZ = target.getBlockZ() >> 4;
            ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);

            CompletableFuture<Chunk> chunkFuture = inFlightChunkLoads.computeIfAbsent(key, k -> {
                CompletableFuture<Chunk> cf = world.getChunkAtAsync(target);
                cf.whenComplete((c, ex) -> inFlightChunkLoads.remove(k));
                return cf;
            });

            chunkFuture.thenAccept(chunk -> dispatchStage5(task, chunk))
                .exceptionally(ex -> {
                    task.future().complete(false);
                    return null;
                });
        }
    }

    private void dispatchStage5(TeleportTask task, Chunk chunk) {
        Player player = task.player();
        Location target = task.target();
        HomeConfig config = configSupplier.get();

        runOnPlayerThread(player, () -> {
            try {
                if (!player.isOnline()) {
                    removeTicket(chunk);
                    task.future().complete(false);
                    return;
                }

                addTicket(chunk);

                if (!isLocationSafe(target)) {
                    removeTicket(chunk);
                    String msg = config.messages().prefix() + config.messages().unsafeLocation();
                    player.sendMessage(miniMessage.deserialize(msg));
                    task.future().complete(false);
                    return;
                }

                String successMsg = config.messages().prefix() + config.messages().teleportSuccess();
                player.sendMessage(miniMessage.deserialize(successMsg, Placeholder.parsed("home", task.home().name())));

                player.teleportAsync(target).thenAccept(success -> {
                    removeTicket(chunk);
                    task.future().complete(success);
                });
            } catch (Throwable t) {
                removeTicket(chunk);
                task.future().complete(false);
            }
        });
    }

    private void addTicket(Chunk chunk) {
        if (chunk == null) return;
        try {
            chunk.addPluginChunkTicket(plugin);
        } catch (Throwable ignored) {}
    }

    private void removeTicket(Chunk chunk) {
        if (chunk == null) return;
        try {
            chunk.removePluginChunkTicket(plugin);
        } catch (Throwable ignored) {}
    }

    private void runOnPlayerThread(Player player, Runnable runnable) {
        try {
            player.getScheduler().run(plugin, task -> runnable.run(), null);
        } catch (NoSuchMethodError | Exception e) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    private void cancelScheduledTask(Object task) {
        if (task == null) return;
        if (task instanceof Integer taskId) {
            Bukkit.getScheduler().cancelTask(taskId);
        } else {
            try {
                task.getClass().getMethod("cancel").invoke(task);
            } catch (Throwable ignored) {}
        }
    }

    private boolean isWorldRestricted(String worldName, HomeConfig config) {
        HomeConfig.HomeWorldRestrictions res = config.worldRestrictions();
        if (res == null || res.worlds() == null) {
            return false;
        }
        boolean listed = res.worlds().stream().anyMatch(w -> w.equalsIgnoreCase(worldName));
        return "WHITELIST".equalsIgnoreCase(res.mode()) ? !listed : listed;
    }
}
