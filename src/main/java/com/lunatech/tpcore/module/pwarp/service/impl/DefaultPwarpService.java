package com.lunatech.tpcore.module.pwarp.service.impl;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.config.PwarpConfig;
import com.lunatech.tpcore.module.pwarp.config.PwarpConfigManager;
import com.lunatech.tpcore.module.pwarp.config.PwarpWorldConfig;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRepository;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultPwarpService implements PwarpService {

    private final JavaPlugin plugin;
    private final Supplier<PwarpConfig> configSupplier;
    private final PwarpRepository repository;
    private final PwarpCache cache;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private record WarmupSession(ScheduledTask task, CompletableFuture<Boolean> future) {}

    private final Map<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
    private final Map<UUID, WarmupSession> warmupTasks = new ConcurrentHashMap<>();

    public DefaultPwarpService(
        JavaPlugin plugin,
        Supplier<PwarpConfig> configSupplier,
        PwarpRepository repository,
        PwarpCache cache
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return this.repository.loadAllPwarps().thenAccept(list -> {
            this.cache.clear();
            for (Pwarp pwarp : list) {
                this.cache.put(pwarp);
            }
            this.plugin.getSLF4JLogger().info("Loaded {} player warps into L1 cache.", list.size());
        });
    }

    @Override
    public CompletableFuture<Boolean> setWarp(Player player, String name, String description) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        if (!config.enabled()) {
            sendMessage(player, config.messages().disabled());
            return CompletableFuture.completedFuture(false);
        }

        if (!player.hasPermission(Permissions.PWARP_SET)) {
            sendMessage(player, config.messages().noPermission());
            return CompletableFuture.completedFuture(false);
        }

        World world = player.getWorld();
        PwarpWorldConfig worldConfig = config.worldConfigs().get(world.getName().toLowerCase(Locale.ROOT));
        if (worldConfig != null && !worldConfig.enabled()) {
            sendMessage(player, config.messages().worldDisabled(), Placeholder.unparsed("world", world.getName()));
            return CompletableFuture.completedFuture(false);
        }

        String warpName = name != null ? name.trim() : "";
        if (warpName.isBlank() || warpName.length() > config.maxNameLength()) {
            sendMessage(player, config.messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        if (this.cache.getByName(warpName).isPresent()) {
            sendMessage(player, config.messages().warpExists(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        int currentCount = this.cache.countByOwner(player.getUniqueId());
        int maxLimit = getMaxWarpLimit(player);
        if (currentCount >= maxLimit) {
            sendMessage(player, config.messages().limitReached(), Placeholder.unparsed("max", String.valueOf(maxLimit)));
            return CompletableFuture.completedFuture(false);
        }

        Location loc = player.getLocation();
        Pwarp newWarp = new Pwarp(
            0,
            player.getUniqueId(),
            player.getName(),
            warpName,
            description != null ? description.trim() : "",
            world.getName(),
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            loc.getYaw(),
            loc.getPitch(),
            config.defaultIconMaterial(),
            false,
            System.currentTimeMillis(),
            0
        );

        this.cache.put(newWarp);
        return this.repository.savePwarp(newWarp).thenApply(v -> {
            sendMessage(player, config.messages().setSuccess(), Placeholder.unparsed("warp", warpName));
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteWarp(Player player, String name) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        String warpName = name != null ? name.trim() : "";
        Optional<Pwarp> existing = this.cache.getByName(warpName);

        if (existing.isEmpty()) {
            sendMessage(player, config.messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        Pwarp pwarp = existing.get();
        boolean isOwner = pwarp.ownerUuid().equals(player.getUniqueId());
        boolean hasAdmin = player.hasPermission(Permissions.PWARP_ADMIN);

        if (!isOwner && !hasAdmin) {
            sendMessage(player, config.messages().noPermission());
            return CompletableFuture.completedFuture(false);
        }

        this.cache.remove(warpName);
        return this.repository.deletePwarp(warpName).thenApply(v -> {
            sendMessage(player, config.messages().delSuccess(), Placeholder.unparsed("warp", warpName));
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> executeTeleport(Player player, String name) {
        String warpName = name != null ? name.trim() : "";
        Optional<Pwarp> pwarp = getWarp(warpName);
        if (pwarp.isEmpty()) {
            sendMessage(player, this.configSupplier.get().messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }
        return executeTeleport(player, pwarp.get());
    }

    @Override
    public CompletableFuture<Boolean> executeTeleport(Player player, Pwarp pwarp) {
        if (player == null || !player.isOnline() || pwarp == null) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        if (!config.enabled()) {
            sendMessage(player, config.messages().disabled());
            return CompletableFuture.completedFuture(false);
        }

        if (!player.hasPermission(Permissions.PWARP_USE)) {
            sendMessage(player, config.messages().noPermission());
            return CompletableFuture.completedFuture(false);
        }

        World world = this.plugin.getServer().getWorld(pwarp.worldName());
        if (world == null) {
            sendMessage(player, config.messages().worldNotFound(), Placeholder.unparsed("world", pwarp.worldName()));
            return CompletableFuture.completedFuture(false);
        }

        PwarpWorldConfig worldConfig = config.worldConfigs().get(world.getName().toLowerCase(Locale.ROOT));
        if (worldConfig != null && !worldConfig.enabled()) {
            sendMessage(player, config.messages().worldDisabled(), Placeholder.unparsed("world", world.getName()));
            return CompletableFuture.completedFuture(false);
        }

        // Check Mounted / Vehicle State
        if (player.isInsideVehicle()) {
            if (worldConfig != null && !worldConfig.allowMounted()) {
                sendMessage(player, config.messages().cannotUseMounted());
                return CompletableFuture.completedFuture(false);
            }
            player.leaveVehicle();
        }

        // Check Cooldown
        UUID uuid = player.getUniqueId();
        if (!player.hasPermission(Permissions.PWARP_BYPASS_COOLDOWN)) {
            long remainingSec = getRemainingCooldownSeconds(uuid);
            if (remainingSec > 0) {
                sendMessage(
                    player,
                    config.messages().cooldownActive(),
                    Placeholder.unparsed("seconds", String.valueOf(remainingSec))
                );
                return CompletableFuture.completedFuture(false);
            }
        }

        int warmupSeconds = worldConfig != null ? worldConfig.warmupSeconds() : 3;
        if (warmupSeconds > 0 && !player.hasPermission(Permissions.PWARP_BYPASS_WARMUP)) {
            if (isWarmingUp(uuid)) {
                return CompletableFuture.completedFuture(false);
            }

            sendMessage(
                player,
                config.messages().warmupStarted(),
                Placeholder.unparsed("warp", pwarp.name()),
                Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
            );

            CompletableFuture<Boolean> warmupFuture = new CompletableFuture<>();
            ScheduledTask task = player.getScheduler().runDelayed(
                this.plugin,
                t -> {
                    this.warmupTasks.remove(uuid);
                    dispatchTeleport(player, world, worldConfig, pwarp).thenAccept(warmupFuture::complete);
                },
                null,
                warmupSeconds * 20L
            );

            if (task != null) {
                this.warmupTasks.put(uuid, new WarmupSession(task, warmupFuture));
                return warmupFuture;
            }
        }

        return dispatchTeleport(player, world, worldConfig, pwarp);
    }

    private CompletableFuture<Boolean> dispatchTeleport(
        Player player,
        World world,
        PwarpWorldConfig worldConfig,
        Pwarp pwarp
    ) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        int chunkX = (int) Math.floor(pwarp.x()) >> 4;
        int chunkZ = (int) Math.floor(pwarp.z()) >> 4;

        return world.getChunkAtAsync(chunkX, chunkZ).thenCompose(chunk -> {
            if (player == null || !player.isOnline()) {
                return CompletableFuture.completedFuture(false);
            }

            Location dest = new Location(world, pwarp.x(), pwarp.y(), pwarp.z(), pwarp.yaw(), pwarp.pitch());

            if (!isLocationSafe(world, pwarp.x(), pwarp.y(), pwarp.z())) {
                sendMessage(player, this.configSupplier.get().messages().teleportFailed());
                return CompletableFuture.completedFuture(false);
            }

            CompletableFuture<Boolean> future = new CompletableFuture<>();
            player.getScheduler().run(
                this.plugin,
                t -> {
                    if (!player.isOnline()) {
                        future.complete(false);
                        return;
                    }

                    player.teleportAsync(dest, TeleportCause.PLUGIN).thenAccept(success -> {
                        if (success) {
                            int cooldownSec = worldConfig != null ? worldConfig.cooldownSeconds() : 10;
                            if (cooldownSec > 0) {
                                this.cooldownMap.put(
                                    player.getUniqueId(),
                                    System.currentTimeMillis() + (cooldownSec * 1000L)
                                );
                            }

                            this.repository.incrementVisits(pwarp.name());

                            sendMessage(
                                player,
                                this.configSupplier.get().messages().teleportSuccess(),
                                Placeholder.unparsed("warp", pwarp.name())
                            );
                        } else {
                            sendMessage(player, this.configSupplier.get().messages().teleportFailed());
                        }
                        future.complete(success);
                    });
                },
                null
            );

            return future;
        }).exceptionally(ex -> {
            sendMessage(player, this.configSupplier.get().messages().teleportFailed());
            return false;
        });
    }

    private boolean isLocationSafe(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

        if (by < world.getMinHeight() || by >= world.getMaxHeight() - 2) {
            return false;
        }

        Material standOn = world.getBlockAt(bx, by - 1, bz).getType();
        Material feet = world.getBlockAt(bx, by, bz).getType();
        Material head = world.getBlockAt(bx, by + 1, bz).getType();

        if (standOn == Material.BEDROCK || isHazard(standOn) || !isPassable(feet) || !isPassable(head)) {
            return false;
        }
        return true;
    }

    private boolean isHazard(Material material) {
        if (material == null || material.isAir()) return true;
        return material == Material.LAVA ||
               material == Material.WATER ||
               material == Material.FIRE ||
               material == Material.SOUL_FIRE ||
               material == Material.MAGMA_BLOCK ||
               material == Material.CACTUS ||
               material == Material.SWEET_BERRY_BUSH ||
               material == Material.WITHER_ROSE ||
               material == Material.POWDER_SNOW ||
               material == Material.VOID_AIR;
    }

    private boolean isPassable(Material material) {
        if (material == null || material.isAir()) return true;
        return !material.isSolid() && !isHazard(material);
    }

    @Override
    public Optional<Pwarp> getWarp(String name) {
        if (name == null) return Optional.empty();
        return this.cache.getByName(name);
    }

    @Override
    public List<Pwarp> getPublicWarps() {
        return this.cache.getAllPublic();
    }

    @Override
    public List<Pwarp> getPlayerWarps(UUID ownerUuid) {
        return this.cache.getByOwner(ownerUuid);
    }

    @Override
    public int getMaxWarpLimit(Player player) {
        if (player == null) return 0;
        if (player.hasPermission(Permissions.PWARP_ADMIN)) {
            return Integer.MAX_VALUE;
        }

        int highestLimit = 0;
        PwarpConfig config = this.configSupplier.get();

        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            String perm = pai.getPermission().toLowerCase(Locale.ROOT);
            if (perm.startsWith("tpcore.pwarp.limit.")) {
                String key = perm.substring("tpcore.pwarp.limit.".length());
                Integer limit = config.warpLimits().get(key);
                if (limit != null && limit > highestLimit) {
                    highestLimit = limit;
                }
            }
        }

        if (highestLimit > 0) {
            return highestLimit;
        }

        Integer defaultLimit = config.warpLimits().get("default");
        return defaultLimit != null ? defaultLimit : 2;
    }

    @Override
    public long getRemainingCooldownSeconds(UUID playerUniqueId) {
        Long expireTime = this.cooldownMap.get(playerUniqueId);
        if (expireTime == null) {
            return 0;
        }
        long remainingMillis = expireTime - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            this.cooldownMap.remove(playerUniqueId);
            return 0;
        }
        return (remainingMillis + 999L) / 1000L;
    }

    @Override
    public boolean isWarmingUp(UUID playerUniqueId) {
        WarmupSession session = this.warmupTasks.get(playerUniqueId);
        return session != null && session.task() != null && !session.task().isCancelled();
    }

    @Override
    public boolean hasActiveWarmups() {
        return !this.warmupTasks.isEmpty();
    }

    @Override
    public void cancelWarmup(UUID playerUniqueId) {
        WarmupSession session = this.warmupTasks.remove(playerUniqueId);
        if (session != null) {
            if (session.task() != null) {
                session.task().cancel();
            }
            if (session.future() != null) {
                session.future().complete(false);
            }
            Player player = this.plugin.getServer().getPlayer(playerUniqueId);
            if (player != null && player.isOnline()) {
                sendMessage(player, this.configSupplier.get().messages().warmupCancelled());
            }
        }
    }

    @Override
    public void shutdown() {
        this.warmupTasks.values().forEach(session -> {
            if (session.task() != null) session.task().cancel();
            if (session.future() != null) session.future().complete(false);
        });
        this.warmupTasks.clear();
        this.cooldownMap.clear();
        this.cache.clear();
    }

    private void sendMessage(Player player, String messageFormat, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (messageFormat == null || messageFormat.isBlank()) {
            return;
        }
        String prefix = this.configSupplier.get().messages().prefix();
        Component messageComponent = this.miniMessage.deserialize(prefix + messageFormat, resolvers);
        player.sendMessage(messageComponent);
    }
}
