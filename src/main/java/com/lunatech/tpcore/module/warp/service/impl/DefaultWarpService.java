package com.lunatech.tpcore.module.warp.service.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.util.MessageFormatter;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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

    private static final int MAX_COOLDOWN_ENTRIES = 2000;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> failedPasswordAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, WarmupSession> activeWarmups = new ConcurrentHashMap<>();
    private final AtomicReference<CompletableFuture<?>> activeMigration = new AtomicReference<>();

    private record WarmupSession(ScheduledTask task, Warp warp) {}

    private void recordCooldown(UUID uuid) {
        if (uuid == null) return;
        long now = System.currentTimeMillis();
        cooldowns.put(uuid, now);
        if (cooldowns.size() > MAX_COOLDOWN_ENTRIES) {
            long cooldownMs = config.cooldownSeconds() * 1000L;
            cooldowns.entrySet().removeIf(entry -> (now - entry.getValue()) >= cooldownMs);
        }
    }

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
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long lastFailed = failedPasswordAttempts.get(uuid);
            if (lastFailed != null && (now - lastFailed) < 2000L) {
                return CompletableFuture.completedFuture(WarpResultStatus.INVALID_PASSWORD);
            }

            if (rawPassword == null || rawPassword.isBlank()) {
                return CompletableFuture.completedFuture(WarpResultStatus.PASSWORD_REQUIRED);
            }
            String inputHash = hashPassword(rawPassword);
            if (!warp.passwordHash().equals(inputHash)) {
                failedPasswordAttempts.put(uuid, now);
                if (failedPasswordAttempts.size() > MAX_COOLDOWN_ENTRIES) {
                    failedPasswordAttempts.entrySet().removeIf(e -> (now - e.getValue()) >= 5000L);
                }
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

        // Check if player is already warming up
        if (activeWarmups.containsKey(uuid)) {
            return CompletableFuture.completedFuture(WarpResultStatus.WARMUP_ALREADY_ACTIVE);
        }

        // Resolve target world
        World world = resolveWorld(warp);
        if (world == null) {
            return CompletableFuture.completedFuture(WarpResultStatus.WORLD_NOT_LOADED);
        }

        Location targetLocation = new Location(world, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());

        int warmupSeconds = config.warmupSeconds();
        boolean bypassWarmup = player.hasPermission("tpcore.warp.bypass.warmup");

        if (warmupSeconds <= 0 || bypassWarmup) {
            return executeTeleportWithSafety(player, targetLocation).thenApply(status -> {
                if (status == WarpResultStatus.SUCCESS) {
                    recordCooldown(uuid);
                }
                return status;
            });
        }

        // Send warmup start notification
        sendPlayerMessage(
            player,
            config.messages().warmupStart(),
            Placeholder.unparsed("warp", warp.name()),
            Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
        );

        CompletableFuture<WarpResultStatus> futureResult = new CompletableFuture<>();

        long delayTicks = warmupSeconds * 20L;
        ScheduledTask scheduledTask = player.getScheduler().runDelayed(plugin, task -> {
            activeWarmups.remove(uuid);
            executeTeleportWithSafety(player, targetLocation).thenAccept(status -> {
                if (status == WarpResultStatus.SUCCESS) {
                    recordCooldown(uuid);
                }
                futureResult.complete(status);
            });
        }, () -> {
            activeWarmups.remove(uuid);
            futureResult.complete(WarpResultStatus.ERROR);
        }, delayTicks);

        if (scheduledTask != null) {
            activeWarmups.put(uuid, new WarmupSession(scheduledTask, warp));
        } else {
            return executeTeleportWithSafety(player, targetLocation).thenApply(status -> {
                if (status == WarpResultStatus.SUCCESS) {
                    recordCooldown(uuid);
                }
                return status;
            });
        }

        return futureResult;
    }

    private CompletableFuture<WarpResultStatus> executeTeleportWithSafety(Player player, Location targetLocation) {
        World world = targetLocation.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(WarpResultStatus.WORLD_NOT_LOADED);
        }

        boolean requireSafety = config.safetyChecks().preventUnsafeTeleport();
        if (!requireSafety) {
            return performAsyncTeleport(player, targetLocation);
        }

        return WarpSafetyInspector.findSafeLocationAsync(
            plugin,
            targetLocation,
            true,
            3,
            config.safetyChecks().preventNetherRoof(),
            config.safetyChecks().maxNetherHeight()
        ).thenCompose(safeLocation -> {
            if (safeLocation == null) {
                return CompletableFuture.completedFuture(WarpResultStatus.UNSAFE_LOCATION);
            }
            return performAsyncTeleport(player, safeLocation);
        });
    }

    private CompletableFuture<WarpResultStatus> performAsyncTeleport(Player player, Location targetLocation) {
        CompletableFuture<WarpResultStatus> future = new CompletableFuture<>();
        player.getScheduler().run(plugin, task -> {
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            if (player.isSleeping()) {
                player.wakeup(false);
            }
            if (player.isGliding()) {
                player.setGliding(false);
            }
            player.setFallDistance(0.0f);

            player.teleportAsync(targetLocation).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    future.complete(WarpResultStatus.SUCCESS);
                } else {
                    future.complete(WarpResultStatus.ERROR);
                }
            }).exceptionally(ex -> {
                logger.error("Failed to teleport player {} to warp location", player.getName(), ex);
                future.complete(WarpResultStatus.ERROR);
                return null;
            });
        }, () -> future.complete(WarpResultStatus.ERROR));
        return future;
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
            return CompletableFuture.completedFuture(WarpResultStatus.INVALID_NAME);
        }

        Optional<Warp> existing = cache.getWarp(warpName);
        if (existing.isPresent() && !overwrite) {
            return CompletableFuture.completedFuture(WarpResultStatus.ALREADY_EXISTS);
        }

        int maxWarps = config.maxWarps();
        if (maxWarps > 0 && existing.isEmpty() && cache.getWarpCount() >= maxWarps) {
            if (!creator.hasPermission("tpcore.warp.bypass.limit") && !creator.hasPermission("tpcore.warp.admin")) {
                return CompletableFuture.completedFuture(WarpResultStatus.LIMIT_REACHED);
            }
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
            sendPlayerMessage(player, config.messages().warmupCancelledMove());
        }
    }

    @Override
    public void cancelWarmupOnDamage(Player player) {
        WarmupSession session = activeWarmups.remove(player.getUniqueId());
        if (session != null) {
            session.task().cancel();
            sendPlayerMessage(player, config.messages().warmupCancelledDamage());
        }
    }

    @Override
    public void cancelWarmupOnQuit(UUID playerUuid) {
        cancelWarmupSession(playerUuid);
        if (playerUuid != null) {
            Long lastTime = cooldowns.get(playerUuid);
            if (lastTime != null && (System.currentTimeMillis() - lastTime) >= (config.cooldownSeconds() * 1000L)) {
                cooldowns.remove(playerUuid);
            }
        }
    }

    @Override
    public long getRemainingCooldownSeconds(UUID playerUuid) {
        if (playerUuid == null) return 0;
        Long lastTime = cooldowns.get(playerUuid);
        if (lastTime == null) return 0;
        long passedMs = System.currentTimeMillis() - lastTime;
        long cooldownMs = config.cooldownSeconds() * 1000L;
        if (passedMs >= cooldownMs) {
            cooldowns.remove(playerUuid);
            return 0;
        }
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

        CompletableFuture<Integer> migrationFuture = CompletableFuture.supplyAsync(() -> {
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
                toRepo.saveAll(warps.values()).join();
                if (isToActive) {
                    cache.populate(warps);
                }
                int count = warps.size();
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

        activeMigration.set(migrationFuture);
        migrationFuture.whenComplete((r, ex) -> activeMigration.compareAndSet(migrationFuture, null));
        return migrationFuture;
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
        failedPasswordAttempts.clear();
        cache.clear();

        CompletableFuture<?> inFlightMigration = activeMigration.get();
        if (inFlightMigration != null && !inFlightMigration.isDone()) {
            try {
                inFlightMigration.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Timed out or interrupted waiting for in-flight warp data migration during close", e);
            }
        }

        return repository.close();
    }

    private World resolveWorld(Warp warp) {
        if (Bukkit.getServer() == null) {
            return null;
        }
        if (warp.worldId() != null) {
            World w = Bukkit.getWorld(warp.worldId());
            if (w != null) return w;
        }
        return Bukkit.getWorld(warp.worldName());
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

    private void sendPlayerMessage(Player player, String template, TagResolver... customResolvers) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        TagResolver combined;
        if (customResolvers == null || customResolvers.length == 0) {
            combined = prefixResolver;
        } else {
            TagResolver[] all = new TagResolver[customResolvers.length + 1];
            all[0] = prefixResolver;
            System.arraycopy(customResolvers, 0, all, 1, customResolvers.length);
            combined = TagResolver.resolver(all);
        }
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
    }
}
