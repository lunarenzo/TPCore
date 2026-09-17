package com.lunatech.tpcore.module.home.engine;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.model.Home;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
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
    private final Long2ObjectMap<CompletableFuture<Chunk>> inFlightChunkLoads = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private final Queue<TeleportTask> taskQueue = new ConcurrentLinkedQueue<>();
    private Object batchTask;

    public record TeleportTask(
        Player player,
        Location target,
        Home home,
        CompletableFuture<Boolean> future,
        long requestedAt
    ) {}

    public ConcurrentTeleportPipelineEngine(Plugin plugin, Supplier<HomeConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");

        startBatchProcessor();
    }

    public CompletableFuture<Boolean> submitTeleport(Player player, Home home, Location target) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(home, "home cannot be null");
        Objects.requireNonNull(target, "target cannot be null");

        HomeConfig config = configSupplier.get();

        if (isWorldRestricted(target.getWorld().getName(), config)) {
            String msg = config.messages().prefix() + config.messages().worldRestricted();
            player.sendMessage(miniMessage.deserialize(msg));
            return CompletableFuture.completedFuture(false);
        }

        World world = target.getWorld();
        if (world == null || target.getY() < world.getMinHeight() || target.getY() >= world.getMaxHeight()) {
            String msg = config.messages().prefix() + config.messages().unsafeLocation();
            player.sendMessage(miniMessage.deserialize(msg));
            return CompletableFuture.completedFuture(false);
        }

        HomeConfig.HomeSafetyConfig safety = config.safetyChecks();
        if (safety.preventNetherRoof() && world.getEnvironment() == World.Environment.NETHER) {
            if (target.getY() > safety.maxNetherHeight()) {
                String msg = config.messages().prefix() + config.messages().unsafeLocation();
                player.sendMessage(miniMessage.deserialize(msg));
                return CompletableFuture.completedFuture(false);
            }
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

        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid() && !ground.isPassable();
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

            processed++;
            Location target = task.target();
            World world = target.getWorld();
            int chunkX = target.getBlockX() >> 4;
            int chunkZ = target.getBlockZ() >> 4;
            long packedKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);

            if (world.isChunkLoaded(chunkX, chunkZ)) {
                dispatchStage5(task, world.getChunkAt(chunkX, chunkZ));
            } else {
                CompletableFuture<Chunk> chunkFuture;
                synchronized (inFlightChunkLoads) {
                    chunkFuture = inFlightChunkLoads.get(packedKey);
                    if (chunkFuture == null) {
                        chunkFuture = world.getChunkAtAsync(target);
                        inFlightChunkLoads.put(packedKey, chunkFuture);
                        chunkFuture.whenComplete((c, ex) -> inFlightChunkLoads.remove(packedKey));
                    }
                }

                chunkFuture.thenAccept(chunk -> {
                    if (chunk != null) {
                        try {
                            chunk.addPluginChunkTicket(plugin);
                        } catch (Throwable ignored) {}
                    }
                    dispatchStage5(task, chunk);
                }).exceptionally(ex -> {
                    task.future().complete(false);
                    return null;
                });
            }
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
