package com.lunatech.tpcore.module.back.service.impl;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.back.cache.BackCache;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.BackRepository;
import com.lunatech.tpcore.module.back.service.BackResultStatus;
import com.lunatech.tpcore.module.back.service.BackService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

public final class DefaultBackService implements BackService {

    private final Plugin plugin;
    private final BackRepository repository;
    private final BackCache cache;
    private final Logger logger;
    private volatile BackConfig config;

    private final BackCooldownManager cooldownManager;
    private final BackWarmupManager warmupManager;
    private final BackProtectionManager protectionManager;
    private final BackDataMigrator dataMigrator;
    private final Set<UUID> backTeleportsInProgress = ConcurrentHashMap.newKeySet();

    public DefaultBackService(Plugin plugin, BackRepository repository, BackCache cache, BackConfig config, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.cooldownManager = new BackCooldownManager();
        BackWarmupRenderer warmupRenderer = new BackWarmupRenderer(MiniMessage.miniMessage());
        this.warmupManager = new BackWarmupManager(plugin, () -> this.config, warmupRenderer, MiniMessage.miniMessage());
        this.protectionManager = new BackProtectionManager(plugin, () -> this.config, MiniMessage.miniMessage());
        this.dataMigrator = new BackDataMigrator(plugin.getDataFolder(), logger, () -> this.config, cache, repository);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return repository.initialize().thenCompose(v -> repository.loadAll()).thenAccept(cache::populate);
    }

    @Override
    public CompletableFuture<BackResultStatus> teleportBack(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        if (!config.enabled()) return CompletableFuture.completedFuture(BackResultStatus.ERROR);

        List<BackLocation> history = cache.getHistory(player.getUniqueId());
        if (history.isEmpty()) return CompletableFuture.completedFuture(BackResultStatus.NO_BACK_LOCATION);

        BackLocation targetLoc = history.stream().filter(loc -> resolveWorld(loc) != null).findFirst().orElse(null);
        if (targetLoc == null) return CompletableFuture.completedFuture(BackResultStatus.WORLD_NOT_LOADED);

        final BackLocation finalLoc = targetLoc;
        return executeBackTeleport(player, finalLoc, uuid -> cache.removeLocation(uuid, finalLoc));
    }

    @Override
    public CompletableFuture<BackResultStatus> teleportDeath(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        if (!config.enabled()) return CompletableFuture.completedFuture(BackResultStatus.ERROR);

        List<BackLocation> history = cache.getHistory(player.getUniqueId());
        if (history.isEmpty()) return CompletableFuture.completedFuture(BackResultStatus.NO_DEATH_LOCATION);

        BackLocation targetLoc = history.stream()
            .filter(loc -> loc.cause() != null && loc.cause().isDeath() && resolveWorld(loc) != null)
            .findFirst().orElse(null);
        if (targetLoc == null) return CompletableFuture.completedFuture(BackResultStatus.NO_DEATH_LOCATION);

        final BackLocation finalLoc = targetLoc;
        return executeBackTeleport(player, finalLoc, uuid -> cache.removeLocation(uuid, finalLoc));
    }

    @Override
    public CompletableFuture<BackResultStatus> teleportToHistory(Player player, int index) {
        Objects.requireNonNull(player, "player cannot be null");
        if (!config.enabled()) return CompletableFuture.completedFuture(BackResultStatus.ERROR);
        List<BackLocation> history = cache.getHistory(player.getUniqueId());
        if (history.isEmpty() || index < 0 || index >= history.size()) {
            return CompletableFuture.completedFuture(BackResultStatus.NO_BACK_LOCATION);
        }
        BackLocation loc = history.get(index);
        return executeBackTeleport(player, loc, uuid -> cache.removeLocation(uuid, loc));
    }

    private CompletableFuture<BackResultStatus> executeBackTeleport(Player player, BackLocation backLoc, Consumer<UUID> onSuccessConsumer) {
        UUID uuid = player.getUniqueId();
        boolean bypassCooldown = player.hasPermission(Permissions.BACK_BYPASS_COOLDOWN);
        if (!bypassCooldown && cooldownManager.isOnCooldown(uuid, config.cooldownSeconds())) {
            return CompletableFuture.completedFuture(BackResultStatus.COOLDOWN_ACTIVE);
        }

        World world = resolveWorld(backLoc);
        if (world == null) return CompletableFuture.completedFuture(BackResultStatus.WORLD_NOT_LOADED);

        Location targetLocation = new Location(world, backLoc.x(), backLoc.y(), backLoc.z(), backLoc.yaw(), backLoc.pitch());
        warmupManager.cancelWarmup(uuid);

        int warmupSeconds = config.warmupSeconds();
        boolean bypassWarmup = player.hasPermission(Permissions.BACK_BYPASS_WARMUP);

        if (warmupSeconds <= 0 || bypassWarmup) {
            return performTeleportWithSafety(player, targetLocation, onSuccessConsumer);
        }

        CompletableFuture<BackResultStatus> future = new CompletableFuture<>();
        warmupManager.startWarmup(player, backLoc, warmupSeconds, () -> {
            if (!player.isOnline() || player.isDead()) {
                future.complete(BackResultStatus.ERROR);
                return;
            }
            World currentWorld = resolveWorld(backLoc);
            if (currentWorld == null) {
                future.complete(BackResultStatus.WORLD_NOT_LOADED);
                return;
            }
            Location freshTarget = new Location(currentWorld, backLoc.x(), backLoc.y(), backLoc.z(), backLoc.yaw(), backLoc.pitch());
            performTeleportWithSafety(player, freshTarget, onSuccessConsumer).thenAccept(future::complete);
        }, () -> future.complete(BackResultStatus.ERROR));

        return future;
    }

    private void preparePlayerForTeleport(Player player) {
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (!player.getPassengers().isEmpty()) {
            player.eject();
        }
        if (player.isSleeping()) {
            player.wakeup(false);
        }
        if (player.isGliding()) {
            player.setGliding(false);
        }
    }

    private CompletableFuture<BackResultStatus> performTeleportWithSafety(Player player, Location targetLocation, Consumer<UUID> onSuccessConsumer) {
        World world = targetLocation.getWorld();
        if (world == null) return CompletableFuture.completedFuture(BackResultStatus.WORLD_NOT_LOADED);

        preparePlayerForTeleport(player);

        if (!config.safetyChecks().preventUnsafeTeleport()) {
            return performAsyncBackTeleport(player, targetLocation, onSuccessConsumer, false);
        }

        CompletableFuture<BackResultStatus> future = new CompletableFuture<>();
        BackSafetyInspector.findSafeLocationAsync(
            plugin,
            targetLocation,
            config.safetyChecks().autoAdjustHazard(),
            config.safetyChecks().hazardSearchRadius(),
            config.safetyChecks().preventNetherRoof(),
            config.safetyChecks().maxNetherHeight()
        ).thenAccept(safeLoc -> player.getScheduler().run(plugin, task -> {
            if (!player.isOnline() || player.isDead()) {
                future.complete(BackResultStatus.ERROR);
                return;
            }
            if (safeLoc == null) {
                future.complete(BackResultStatus.UNSAFE_LOCATION);
                return;
            }
            preparePlayerForTeleport(player);
            boolean adjusted = !safeLoc.equals(targetLocation);
            performAsyncBackTeleport(player, safeLoc, onSuccessConsumer, adjusted).thenAccept(future::complete);
        }, () -> future.complete(BackResultStatus.ERROR))).exceptionally(ex -> {
            future.complete(BackResultStatus.ERROR);
            return null;
        });

        return future;
    }

    private CompletableFuture<BackResultStatus> performAsyncBackTeleport(Player player, Location finalLocation, Consumer<UUID> onSuccessConsumer, boolean hazardAdjusted) {
        UUID uuid = player.getUniqueId();
        backTeleportsInProgress.add(uuid);

        return player.teleportAsync(finalLocation).thenApply(success -> {
            backTeleportsInProgress.remove(uuid);
            if (Boolean.TRUE.equals(success)) {
                cooldownManager.applyCooldown(uuid);
                protectionManager.grantTeleportProtection(player);
                if (onSuccessConsumer != null) {
                    onSuccessConsumer.accept(uuid);
                    persistPlayerHistoryAsync(uuid);
                }
                return hazardAdjusted ? BackResultStatus.SUCCESS_ADJUSTED_HAZARD : BackResultStatus.SUCCESS;
            }
            return BackResultStatus.ERROR;
        }).exceptionally(ex -> {
            backTeleportsInProgress.remove(uuid);
            return BackResultStatus.ERROR;
        });
    }

    @Override
    public void recordLocation(Player player, Location location, BackCause cause) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(location, "location cannot be null");

        if (!config.enabled()) return;
        if (cause == BackCause.TELEPORT && !config.trackTeleports()) return;
        if (cause == BackCause.PORTAL && !config.trackPortals()) return;

        UUID uuid = player.getUniqueId();
        if (backTeleportsInProgress.contains(uuid) && !config.trackBackTeleports()) {
            return;
        }

        Optional<BackLocation> lastOpt = cache.peekLastLocation(uuid);
        if (lastOpt.isPresent()) {
            BackLocation last = lastOpt.get();
            if (last.isSameWorld(location.getWorld().getUID(), location.getWorld().getName())) {
                double distSq = last.distanceSquared(location.getX(), location.getY(), location.getZ());
                if (last.cause() == BackCause.DEATH && cause == BackCause.TELEPORT && distSq < 16.0) {
                    long elapsedMs = System.currentTimeMillis() - last.timestamp();
                    if (elapsedMs < 30_000L) {
                        return;
                    }
                }
                double minDist = config.minTeleportDistance();
                if (distSq < (minDist * minDist)) return;
            }
        }

        cache.pushLocation(uuid, toBackLocation(location, cause != null ? cause : BackCause.TELEPORT), config.maxHistoryDepth());
    }

    @Override
    public void recordDeathLocation(Player player, Location location) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(location, "location cannot be null");
        if (!config.enabled() || !config.trackDeaths()) return;

        UUID uuid = player.getUniqueId();
        cache.pushLocation(uuid, toBackLocation(location, BackCause.DEATH), config.maxHistoryDepth());
        if (config.persistDeathLocations()) persistPlayerHistoryAsync(uuid);
    }

    private BackLocation toBackLocation(Location loc, BackCause cause) {
        return new BackLocation(
            loc.getWorld().getUID(), loc.getWorld().getName(),
            loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
            System.currentTimeMillis(), cause
        );
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
        if (config.cancelOnMove()) warmupManager.handlePlayerMove(player);
    }

    @Override
    public void cancelWarmupOnDamage(Player player) {
        if (config.cancelOnDamage()) warmupManager.handlePlayerDamage(player);
    }

    @Override
    public void cancelWarmupOnQuit(UUID playerUuid) {
        warmupManager.handlePlayerQuit(playerUuid);
        cooldownManager.removeCooldown(playerUuid);
        protectionManager.evict(playerUuid);
        cache.clearPlayerHistory(playerUuid);
        backTeleportsInProgress.remove(playerUuid);
    }

    @Override
    public long getRemainingCooldownSeconds(UUID playerUuid) {
        return cooldownManager.getRemainingCooldownSeconds(playerUuid, config.cooldownSeconds());
    }

    @Override
    public void grantTeleportProtection(Player player) {
        protectionManager.grantTeleportProtection(player);
    }

    @Override
    public boolean hasTeleportProtection(UUID playerId) {
        return protectionManager.hasTeleportProtection(playerId);
    }

    @Override
    public long getTeleportProtectionStartTime(UUID playerId) {
        return protectionManager.getTeleportProtectionStartTime(playerId);
    }

    @Override
    public void stripTeleportProtection(UUID playerId) {
        protectionManager.stripTeleportProtection(playerId);
    }

    @Override
    public boolean handlePlayerProtectionDamage(Player victim, Player attacker, boolean isPvp) {
        return protectionManager.handlePlayerProtectionDamage(victim, attacker, isPvp);
    }

    @Override
    public boolean isProtected(UUID playerUuid) {
        return protectionManager.hasTeleportProtection(playerUuid);
    }

    @Override
    public void updateConfig(BackConfig newConfig) {
        if (newConfig != null) this.config = newConfig;
    }

    @Override
    public CompletableFuture<Integer> migrateData(String fromStorage, String toStorage) {
        return dataMigrator.migrateData(fromStorage, toStorage);
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
        warmupManager.clear();
        cooldownManager.clear();
        protectionManager.clear();
        cache.clear();
        backTeleportsInProgress.clear();
        return repository.close();
    }

    private World resolveWorld(BackLocation backLoc) {
        if (backLoc.worldId() != null) {
            World w = Bukkit.getWorld(backLoc.worldId());
            if (w != null) return w;
        }
        return Bukkit.getWorld(backLoc.worldName());
    }
}
