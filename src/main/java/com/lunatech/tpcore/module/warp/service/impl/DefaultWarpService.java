package com.lunatech.tpcore.module.warp.service.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
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

public final class DefaultWarpService implements WarpService {

    private final Plugin plugin;
    private final WarpRepository repository;
    private final WarpCache cache;
    private final Logger logger;
    private volatile WarpConfig config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, WarmupSession> activeWarmups = new ConcurrentHashMap<>();

    private record WarmupSession(ScheduledTask task, Warp warp) {}

    public DefaultWarpService(Plugin plugin, WarpRepository repository, WarpCache cache, WarpConfig config, Logger logger) {
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
    public CompletableFuture<WarpResultStatus> teleportToWarp(Player player, String warpName, String rawPassword) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(warpName, "warpName cannot be null");

        if (!config.enabled()) {
            return CompletableFuture.completedFuture(WarpResultStatus.ERROR);
        }

        if (!isValidWarpName(warpName)) {
            return CompletableFuture.completedFuture(WarpResultStatus.WARP_NOT_FOUND);
        }

        Optional<Warp> warpOpt = cache.getWarp(warpName);
        if (warpOpt.isEmpty()) {
            return CompletableFuture.completedFuture(WarpResultStatus.WARP_NOT_FOUND);
        }

        Warp warp = warpOpt.get();

        // Check permission node if gated
        if (warp.permissionGated()) {
            String permNode = "tpcore.warp." + warp.name().toLowerCase();
            if (!player.hasPermission(permNode) && !player.hasPermission("tpcore.warp.admin")) {
                return CompletableFuture.completedFuture(WarpResultStatus.NO_PERMISSION);
            }
        }

        // Check password protection
        if (warp.hasPassword() && !player.hasPermission("tpcore.warp.admin") && !player.hasPermission("tpcore.warp.bypass.password")) {
            if (rawPassword == null || rawPassword.isBlank()) {
                return CompletableFuture.completedFuture(WarpResultStatus.PASSWORD_REQUIRED);
            }
            String inputHash = hashPassword(rawPassword);
            if (!warp.passwordHash().equals(inputHash)) {
                return CompletableFuture.completedFuture(WarpResultStatus.INVALID_PASSWORD);
            }
        }

        // Check cooldown
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownMs = config.cooldownSeconds() * 1000L;
        Long lastTime = cooldowns.get(uuid);
        if (lastTime != null && (now - lastTime) < cooldownMs && !player.hasPermission("tpcore.warp.bypass.cooldown")) {
            return CompletableFuture.completedFuture(WarpResultStatus.COOLDOWN_ACTIVE);
        }

        // Resolve target world
        World world = resolveWorld(warp);
        if (world == null) {
            return CompletableFuture.completedFuture(WarpResultStatus.WORLD_NOT_LOADED);
        }

        Location targetLocation = new Location(world, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());

        // Cancel any existing warmup session for this player
        cancelWarmupSession(uuid);

        int warmupSeconds = config.warmupSeconds();
        boolean bypassWarmup = player.hasPermission("tpcore.warp.bypass.warmup");

        if (warmupSeconds <= 0 || bypassWarmup) {
            cooldowns.put(uuid, now);
            return executeTeleportWithSafety(player, targetLocation);
        }

        // Send warmup start notification
        String startMsg = config.messages().prefix() + config.messages().warmupStart();
        player.sendMessage(miniMessage.deserialize(
            startMsg,
            Placeholder.unparsed("warp", warp.name()),
            Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
        ));

        CompletableFuture<WarpResultStatus> futureResult = new CompletableFuture<>();

        long delayTicks = warmupSeconds * 20L;
        ScheduledTask scheduledTask = player.getScheduler().runDelayed(plugin, task -> {
            activeWarmups.remove(uuid);
            cooldowns.put(uuid, System.currentTimeMillis());
            executeTeleportWithSafety(player, targetLocation).thenAccept(futureResult::complete);
        }, () -> {
            activeWarmups.remove(uuid);
            futureResult.complete(WarpResultStatus.ERROR);
        }, delayTicks);

        if (scheduledTask != null) {
            activeWarmups.put(uuid, new WarmupSession(scheduledTask, warp));
        } else {
            return executeTeleportWithSafety(player, targetLocation);
        }

        return futureResult;
    }

    private CompletableFuture<WarpResultStatus> executeTeleportWithSafety(Player player, Location targetLocation) {
        World world = targetLocation.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(WarpResultStatus.WORLD_NOT_LOADED);
        }

        boolean requireSafety = config.safetyChecks().preventUnsafeTeleport();
        int chunkX = Math.floorDiv(targetLocation.getBlockX(), 16);
        int chunkZ = Math.floorDiv(targetLocation.getBlockZ(), 16);

        // If target chunk is already loaded, check safety BEFORE teleporting player
        if (requireSafety && world.isChunkLoaded(chunkX, chunkZ)) {
            if (!isLocationSafe(targetLocation)) {
                return CompletableFuture.completedFuture(WarpResultStatus.UNSAFE_LOCATION);
            }
            return performAsyncTeleport(player, targetLocation);
        }

        // If chunk is not loaded, request async chunk load before safety verification
        if (requireSafety && !world.isChunkLoaded(chunkX, chunkZ)) {
            CompletableFuture<WarpResultStatus> future = new CompletableFuture<>();
            world.getChunkAtAsync(targetLocation).thenAccept(chunk -> {
                player.getScheduler().run(plugin, task -> {
                    if (!isLocationSafe(targetLocation)) {
                        future.complete(WarpResultStatus.UNSAFE_LOCATION);
                        return;
                    }
                    performAsyncTeleport(player, targetLocation).thenAccept(future::complete);
                }, () -> future.complete(WarpResultStatus.ERROR));
            }).exceptionally(ex -> {
                future.complete(WarpResultStatus.ERROR);
                return null;
            });
            return future;
        }

        return performAsyncTeleport(player, targetLocation);
    }

    private CompletableFuture<WarpResultStatus> performAsyncTeleport(Player player, Location targetLocation) {
        return player.teleportAsync(targetLocation).thenApply(success -> {
            if (Boolean.TRUE.equals(success)) {
                return WarpResultStatus.SUCCESS;
            } else {
                return WarpResultStatus.ERROR;
            }
        });
    }

    @Override
    public CompletableFuture<WarpResultStatus> teleportOtherToWarp(Player sender, Player target, String warpName) {
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(warpName, "warpName cannot be null");

        if (!isValidWarpName(warpName)) {
            return CompletableFuture.completedFuture(WarpResultStatus.WARP_NOT_FOUND);
        }

        Optional<Warp> warpOpt = cache.getWarp(warpName);
        if (warpOpt.isEmpty()) {
            return CompletableFuture.completedFuture(WarpResultStatus.WARP_NOT_FOUND);
        }

        Warp warp = warpOpt.get();
        World world = resolveWorld(warp);
        if (world == null) {
            return CompletableFuture.completedFuture(WarpResultStatus.WORLD_NOT_LOADED);
        }

        Location targetLocation = new Location(world, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());
        return executeTeleportWithSafety(target, targetLocation);
    }

    @Override
    public CompletableFuture<WarpResultStatus> setWarp(Player creator, String warpName, boolean overwrite, String rawPassword, String category) {
        Objects.requireNonNull(creator, "creator cannot be null");
        Objects.requireNonNull(warpName, "warpName cannot be null");

        if (!isValidWarpName(warpName)) {
            return CompletableFuture.completedFuture(WarpResultStatus.ERROR);
        }

        Optional<Warp> existing = cache.getWarp(warpName);
        if (existing.isPresent() && !overwrite) {
            return CompletableFuture.completedFuture(WarpResultStatus.ALREADY_EXISTS);
        }

        Location loc = creator.getLocation();
        String passwordHash = (rawPassword != null && !rawPassword.isBlank()) ? hashPassword(rawPassword) : null;
        String finalCat = (category != null && !category.isBlank()) ? category : config.defaultCategory();

        Warp warp = new Warp(
            warpName,
            loc.getWorld().getUID(),
            loc.getWorld().getName(),
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            loc.getYaw(),
            loc.getPitch(),
            creator.getUniqueId(),
            finalCat,
            passwordHash,
            config.defaultPermissionGated(),
            System.currentTimeMillis()
        );

        cache.putWarp(warp);
        return repository.save(warp).thenApply(v -> WarpResultStatus.SUCCESS);
    }

    @Override
    public CompletableFuture<WarpResultStatus> deleteWarp(String warpName) {
        Objects.requireNonNull(warpName, "warpName cannot be null");

        if (!isValidWarpName(warpName)) {
            return CompletableFuture.completedFuture(WarpResultStatus.WARP_NOT_FOUND);
        }

        Optional<Warp> existing = cache.getWarp(warpName);
        if (existing.isEmpty()) {
            return CompletableFuture.completedFuture(WarpResultStatus.WARP_NOT_FOUND);
        }

        cache.removeWarp(warpName);
        return repository.delete(warpName).thenApply(v -> WarpResultStatus.SUCCESS);
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
    public Optional<Warp> getWarp(String warpName) {
        if (!isValidWarpName(warpName)) {
            return Optional.empty();
        }
        return cache.getWarp(warpName);
    }

    @Override
    public Collection<Warp> getAllWarps() {
        return cache.getAllWarps();
    }

    @Override
    public List<Warp> getWarpsByCategory(String category) {
        return cache.getWarpsByCategory(category);
    }

    @Override
    public Set<String> getCategories() {
        return cache.getCategories();
    }

    @Override
    public void updateConfig(WarpConfig newConfig) {
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

            WarpRepository fromRepo = isFromActive ? repository : createRepoForType(from);
            WarpRepository toRepo = isToActive ? repository : createRepoForType(to);

            boolean tempFrom = !isFromActive;
            boolean tempTo = !isToActive;

            try {
                if (tempFrom) {
                    fromRepo.initialize().join();
                }
                if (tempTo) {
                    toRepo.initialize().join();
                }

                Map<String, Warp> warps = fromRepo.loadAll().join();
                int count = 0;
                for (Warp warp : warps.values()) {
                    toRepo.save(warp).join();
                    if (isToActive) {
                        cache.putWarp(warp);
                    }
                    count++;
                }
                logger.info("Successfully migrated {} warps from {} storage to {} storage.", count, from, to);
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

    private WarpRepository createRepoForType(String type) {
        if ("YAML".equalsIgnoreCase(type)) {
            return new YamlWarpRepository(plugin.getDataFolder(), logger);
        } else {
            return new SqliteWarpRepository(plugin.getDataFolder(), logger);
        }
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
        return repository.close();
    }

    private World resolveWorld(Warp warp) {
        if (warp.worldId() != null) {
            World w = Bukkit.getWorld(warp.worldId());
            if (w != null) return w;
        }
        return Bukkit.getWorld(warp.worldName());
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

    private boolean isValidWarpName(String name) {
        if (name == null || name.isBlank() || name.length() > 32) {
            return false;
        }
        return name.matches("^[a-zA-Z0-9_-]+$");
    }

    private String hashPassword(String password) {
        if (password == null || password.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("Failed to compute SHA-256 hash", e);
            return password;
        }
    }
}
