package com.lunatech.tpcore.module.back.service.impl;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.cache.BackCache;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.BackRepository;
import com.lunatech.tpcore.module.back.repository.impl.SqliteBackRepository;
import com.lunatech.tpcore.module.back.repository.impl.YamlBackRepository;
import com.lunatech.tpcore.module.back.service.BackResultStatus;
import com.lunatech.tpcore.module.back.service.BackService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

public final class DefaultBackService implements BackService {

    private final Plugin plugin;
    private final BackRepository repository;
    private final BackCache cache;
    private final Logger logger;
    private volatile BackConfig config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, WarmupSession> activeWarmups = new ConcurrentHashMap<>();
    private final Set<UUID> backTeleportInProgress = ConcurrentHashMap.newKeySet();

    private record WarmupSession(ScheduledTask task, BackLocation targetBackLoc) {}

    public DefaultBackService(Plugin plugin, BackRepository repository, BackCache cache, BackConfig config, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return repository.initialize()
            .thenCompose(v -> repository.loadAll())
            .thenAccept(cache::populate);
    }

    @Override
    public CompletableFuture<BackResultStatus> teleportBack(Player player) {
        Objects.requireNonNull(player, "player cannot be null");

        if (!config.enabled()) {
            return CompletableFuture.completedFuture(BackResultStatus.ERROR);
        }

        Optional<BackLocation> locOpt = cache.peekLastLocation(player.getUniqueId());
        if (locOpt.isEmpty()) {
            return CompletableFuture.completedFuture(BackResultStatus.NO_BACK_LOCATION);
        }

        return executeBackTeleport(player, locOpt.get(), true);
    }

    @Override
    public CompletableFuture<BackResultStatus> teleportDeath(Player player) {
        Objects.requireNonNull(player, "player cannot be null");

        if (!config.enabled()) {
            return CompletableFuture.completedFuture(BackResultStatus.ERROR);
        }

        Optional<BackLocation> locOpt = cache.peekLastDeathLocation(player.getUniqueId());
        if (locOpt.isEmpty()) {
            return CompletableFuture.completedFuture(BackResultStatus.NO_DEATH_LOCATION);
        }

        return executeBackTeleport(player, locOpt.get(), false);
    }

    @Override
    public CompletableFuture<BackResultStatus> teleportToHistory(Player player, int index) {
        Objects.requireNonNull(player, "player cannot be null");

        if (!config.enabled()) {
            return CompletableFuture.completedFuture(BackResultStatus.ERROR);
        }

        List<BackLocation> history = cache.getHistory(player.getUniqueId());
        if (history.isEmpty() || index < 0 || index >= history.size()) {
            return CompletableFuture.completedFuture(BackResultStatus.NO_BACK_LOCATION);
        }

        BackLocation loc = history.get(index);
        return executeBackTeleport(player, loc, false);
    }

    private CompletableFuture<BackResultStatus> executeBackTeleport(Player player, BackLocation backLoc, boolean popOnSuccess) {
        UUID uuid = player.getUniqueId();

        long now = System.currentTimeMillis();
        long cooldownMs = config.cooldownSeconds() * 1000L;
        Long lastTime = cooldowns.get(uuid);
        if (lastTime != null && (now - lastTime) < cooldownMs && !player.hasPermission("tpcore.back.bypass.cooldown")) {
            return CompletableFuture.completedFuture(BackResultStatus.COOLDOWN_ACTIVE);
        }

        World world = resolveWorld(backLoc);
        if (world == null) {
            return CompletableFuture.completedFuture(BackResultStatus.WORLD_NOT_LOADED);
        }

        Location targetLocation = new Location(world, backLoc.x(), backLoc.y(), backLoc.z(), backLoc.yaw(), backLoc.pitch());

        cancelWarmupSession(uuid);

        int warmupSeconds = config.warmupSeconds();
        boolean bypassWarmup = player.hasPermission("tpcore.back.bypass.warmup");

        if (warmupSeconds <= 0 || bypassWarmup) {
            cooldowns.put(uuid, now);
            return performTeleportWithSafety(player, targetLocation, backLoc, popOnSuccess);
        }

        String startMsg = config.messages().prefix() + config.messages().warmupStart();
        player.sendMessage(miniMessage.deserialize(startMsg, Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))));

        CompletableFuture<BackResultStatus> futureResult = new CompletableFuture<>();

        long delayTicks = warmupSeconds * 20L;
        ScheduledTask scheduledTask = player.getScheduler().runDelayed(plugin, task -> {
            activeWarmups.remove(uuid);
            cooldowns.put(uuid, System.currentTimeMillis());
            performTeleportWithSafety(player, targetLocation, backLoc, popOnSuccess).thenAccept(futureResult::complete);
        }, () -> {
            activeWarmups.remove(uuid);
            futureResult.complete(BackResultStatus.ERROR);
        }, delayTicks);

        if (scheduledTask != null) {
            activeWarmups.put(uuid, new WarmupSession(scheduledTask, backLoc));
        } else {
            return performTeleportWithSafety(player, targetLocation, backLoc, popOnSuccess);
        }

        return futureResult;
    }

    private CompletableFuture<BackResultStatus> performTeleportWithSafety(Player player, Location targetLocation, BackLocation backLoc, boolean popOnSuccess) {
        World world = targetLocation.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(BackResultStatus.WORLD_NOT_LOADED);
        }

        boolean requireSafety = config.safetyChecks().preventUnsafeTeleport();
        int chunkX = Math.floorDiv(targetLocation.getBlockX(), 16);
        int chunkZ = Math.floorDiv(targetLocation.getBlockZ(), 16);

        if (requireSafety && world.isChunkLoaded(chunkX, chunkZ)) {
            Location finalLoc = resolveSafeLocation(targetLocation);
            if (finalLoc == null) {
                return CompletableFuture.completedFuture(BackResultStatus.UNSAFE_LOCATION);
            }
            boolean adjusted = !finalLoc.equals(targetLocation);
            return performAsyncBackTeleport(player, finalLoc, popOnSuccess, adjusted);
        }

        if (requireSafety && !world.isChunkLoaded(chunkX, chunkZ)) {
            CompletableFuture<BackResultStatus> future = new CompletableFuture<>();
            world.getChunkAtAsync(targetLocation).thenAccept(chunk -> {
                player.getScheduler().run(plugin, task -> {
                    Location finalLoc = resolveSafeLocation(targetLocation);
                    if (finalLoc == null) {
                        future.complete(BackResultStatus.UNSAFE_LOCATION);
                        return;
                    }
                    boolean adjusted = !finalLoc.equals(targetLocation);
                    performAsyncBackTeleport(player, finalLoc, popOnSuccess, adjusted).thenAccept(future::complete);
                }, () -> future.complete(BackResultStatus.ERROR));
            }).exceptionally(ex -> {
                future.complete(BackResultStatus.ERROR);
                return null;
            });
            return future;
        }

        return performAsyncBackTeleport(player, targetLocation, popOnSuccess, false);
    }

    private CompletableFuture<BackResultStatus> performAsyncBackTeleport(Player player, Location finalLocation, boolean popOnSuccess, boolean hazardAdjusted) {
        UUID uuid = player.getUniqueId();
        backTeleportInProgress.add(uuid);

        return player.teleportAsync(finalLocation).thenApply(success -> {
            backTeleportInProgress.remove(uuid);
            if (Boolean.TRUE.equals(success)) {
                if (popOnSuccess) {
                    cache.popLastLocation(uuid);
                    persistPlayerHistoryAsync(uuid);
                }
                return hazardAdjusted ? BackResultStatus.SUCCESS_ADJUSTED_HAZARD : BackResultStatus.SUCCESS;
            } else {
                return BackResultStatus.ERROR;
            }
        }).exceptionally(ex -> {
            backTeleportInProgress.remove(uuid);
            return BackResultStatus.ERROR;
        });
    }

    private Location resolveSafeLocation(Location targetLocation) {
        if (isLocationSafe(targetLocation)) {
            return targetLocation;
        }

        if (!config.safetyChecks().autoAdjustHazard()) {
            return null;
        }

        int radius = Math.max(1, Math.min(10, config.safetyChecks().hazardSearchRadius()));
        World world = targetLocation.getWorld();
        if (world == null) return null;

        int baseX = targetLocation.getBlockX();
        int baseY = targetLocation.getBlockY();
        int baseZ = targetLocation.getBlockZ();

        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        Location cand = new Location(world, baseX + dx + 0.5, baseY + dy, baseZ + dz + 0.5, targetLocation.getYaw(), targetLocation.getPitch());
                        if (isLocationSafe(cand)) {
                            return cand;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void recordLocation(Player player, Location location, BackCause cause) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(location, "location cannot be null");

        if (!config.enabled()) return;
        if (cause == BackCause.TELEPORT && !config.trackTeleports()) return;
        if (cause == BackCause.PORTAL && !config.trackPortals()) return;

        UUID uuid = player.getUniqueId();
        if (backTeleportInProgress.contains(uuid)) return;

        Optional<BackLocation> lastOpt = cache.peekLastLocation(uuid);
        if (lastOpt.isPresent()) {
            BackLocation last = lastOpt.get();
            if (last.isSameWorld(location.getWorld().getUID(), location.getWorld().getName())) {
                double distSq = last.distanceSquared(location.getX(), location.getY(), location.getZ());
                double minDist = config.minTeleportDistance();
                if (distSq < (minDist * minDist)) {
                    return;
                }
            }
        }


        BackLocation backLoc = new BackLocation(
            location.getWorld().getUID(),
            location.getWorld().getName(),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch(),
            System.currentTimeMillis(),
            cause != null ? cause : BackCause.TELEPORT
        );

        cache.pushLocation(uuid, backLoc, config.maxHistoryDepth());
    }

    @Override
    public void recordDeathLocation(Player player, Location location) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(location, "location cannot be null");

        if (!config.enabled() || !config.trackDeaths()) return;

        UUID uuid = player.getUniqueId();
        BackLocation deathLoc = new BackLocation(
            location.getWorld().getUID(),
            location.getWorld().getName(),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch(),
            System.currentTimeMillis(),
            BackCause.DEATH
        );

        cache.pushLocation(uuid, deathLoc, config.maxHistoryDepth());

        if (config.persistDeathLocations()) {
            persistPlayerHistoryAsync(uuid);
        }
    }

    private void persistPlayerHistoryAsync(UUID uuid) {
        List<BackLocation> history = cache.getHistory(uuid);
        repository.savePlayerHistory(uuid, history);
    }

    @Override
    public Optional<BackLocation> getLastLocation(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return cache.peekLastLocation(player.getUniqueId());
    }

    @Override
    public List<BackLocation> getHistory(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        return cache.getHistory(player.getUniqueId());
    }

    @Override
    public void clearHistory(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        UUID uuid = player.getUniqueId();
        cache.clearPlayerHistory(uuid);
        repository.deletePlayerHistory(uuid);
    }

    @Override
    public void cancelWarmupOnMove(Player player) {
        WarmupSession session = activeWarmups.remove(player.getUniqueId());
        if (session != null) {
            session.task().cancel();
            String msg = config.messages().prefix() + config.messages().warmupCancelledMove();
            player.sendMessage(miniMessage.deserialize(msg));
        }
    }

    @Override
    public void cancelWarmupOnDamage(Player player) {
        WarmupSession session = activeWarmups.remove(player.getUniqueId());
        if (session != null) {
            session.task().cancel();
            String msg = config.messages().prefix() + config.messages().warmupCancelledDamage();
            player.sendMessage(miniMessage.deserialize(msg));
        }
    }

    @Override
    public void cancelWarmupOnQuit(UUID playerUuid) {
        cancelWarmupSession(playerUuid);
        cooldowns.remove(playerUuid);
        cache.clearPlayerHistory(playerUuid);
        backTeleportInProgress.remove(playerUuid);
    }

    @Override
    public long getRemainingCooldownSeconds(UUID playerUuid) {
        if (playerUuid == null) return 0;
        Long lastTime = cooldowns.get(playerUuid);
        if (lastTime == null) return 0;
        long passedMs = System.currentTimeMillis() - lastTime;
        long cooldownMs = config.cooldownSeconds() * 1000L;
        if (passedMs >= cooldownMs) return 0;
        return Math.max(1, (cooldownMs - passedMs + 999) / 1000);
    }

    private void cancelWarmupSession(UUID uuid) {
        WarmupSession session = activeWarmups.remove(uuid);
        if (session != null && session.task() != null) {
            session.task().cancel();
        }
    }

    @Override
    public void updateConfig(BackConfig newConfig) {
        if (newConfig != null) {
            this.config = newConfig;
        }
    }

    @Override
    public CompletableFuture<Integer> migrateData(String fromStorage, String toStorage) {
        if (fromStorage == null || toStorage == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Storage engine types cannot be null"));
        }
        String from = fromStorage.trim().toUpperCase();
        String to = toStorage.trim().toUpperCase();

        if (!isValidStorageType(from) || !isValidStorageType(to)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("INVALID_STORAGE_TYPE"));
        }
        if (from.equals(to)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("SAME_STORAGE_TYPE"));
        }

        return CompletableFuture.supplyAsync(() -> {
            String activeType = (config.storage() != null && "YAML".equalsIgnoreCase(config.storage().type())) ? "YAML" : "SQLITE";

            boolean isFromActive = from.equals(activeType);
            boolean isToActive = to.equals(activeType);

            BackRepository fromRepo = isFromActive ? repository : createRepoForType(from);
            BackRepository toRepo = isToActive ? repository : createRepoForType(to);

            boolean tempFrom = !isFromActive;
            boolean tempTo = !isToActive;

            try {
                if (tempFrom) {
                    fromRepo.initialize().join();
                }
                if (tempTo) {
                    toRepo.initialize().join();
                }

                Map<UUID, List<BackLocation>> allData = fromRepo.loadAll().join();
                int count = 0;
                for (Map.Entry<UUID, List<BackLocation>> entry : allData.entrySet()) {
                    UUID playerUuid = entry.getKey();
                    List<BackLocation> history = entry.getValue();
                    toRepo.savePlayerHistory(playerUuid, history).join();
                    if (isToActive) {
                        cache.pushLocation(playerUuid, history.get(0), config.maxHistoryDepth());
                    }
                    count += history.size();
                }
                logger.info("Successfully migrated {} back entries from {} storage to {} storage.", count, from, to);
                return count;
            } finally {
                if (tempFrom && fromRepo != null) {
                    try {
                        fromRepo.close().join();
                    } catch (Exception e) {
                        logger.error("Failed to close temporary migration source repository", e);
                    }
                }
                if (tempTo && toRepo != null) {
                    try {
                        toRepo.close().join();
                    } catch (Exception e) {
                        logger.error("Failed to close temporary migration target repository", e);
                    }
                }
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    private boolean isValidStorageType(String type) {
        return "SQLITE".equalsIgnoreCase(type) || "YAML".equalsIgnoreCase(type);
    }

    private BackRepository createRepoForType(String type) {
        if ("YAML".equalsIgnoreCase(type)) {
            return new YamlBackRepository(plugin.getDataFolder(), logger);
        } else {
            return new SqliteBackRepository(plugin.getDataFolder(), logger);
        }
    }

    @Override
    public CompletableFuture<Void> loadPlayerHistoryAsync(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        return repository.loadPlayerHistory(playerUuid).thenAccept(history -> {
            if (history != null && !history.isEmpty()) {
                cache.clearPlayerHistory(playerUuid);
                for (int i = history.size() - 1; i >= 0; i--) {
                    cache.pushLocation(playerUuid, history.get(i), config.maxHistoryDepth());
                }
            }
        });
    }


    @Override
    public CompletableFuture<Void> close() {

        for (WarmupSession session : activeWarmups.values()) {
            if (session.task() != null) {
                session.task().cancel();
            }
        }
        activeWarmups.clear();
        cooldowns.clear();
        cache.clear();
        backTeleportInProgress.clear();
        return repository.close();
    }

    private World resolveWorld(BackLocation backLoc) {
        if (backLoc.worldId() != null) {
            World w = Bukkit.getWorld(backLoc.worldId());
            if (w != null) return w;
        }
        return Bukkit.getWorld(backLoc.worldName());
    }

    private boolean isLocationSafe(Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        double y = location.getY();
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return false;
        }

        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);

        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return false;
        }
        if (isDangerousBlock(feet.getType()) || isDangerousBlock(head.getType())) {
            return false;
        }

        if (config.safetyChecks().preventNetherRoof() && world.getEnvironment() == World.Environment.NETHER) {
            if (y >= config.safetyChecks().maxNetherHeight()) {
                return false;
            }
        }
        return ground.getType().isSolid() || feet.getType() == Material.WATER;
    }

    private boolean isDangerousBlock(Material material) {
        return material == Material.LAVA
            || material == Material.FIRE
            || material == Material.SOUL_FIRE
            || material == Material.POWDER_SNOW;
    }
}
