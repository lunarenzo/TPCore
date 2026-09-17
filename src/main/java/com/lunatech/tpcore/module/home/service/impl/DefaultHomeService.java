package com.lunatech.tpcore.module.home.service.impl;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.cache.HomeCache;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.HomeService;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class DefaultHomeService implements HomeService, Listener {

    private final Plugin plugin;
    private final HomeRepository repository;
    private final HomeCache cache;
    private final Supplier<HomeConfig> configSupplier;
    private final Map<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();

    private record ActiveWarmup(Location startLocation, CompletableFuture<Boolean> future, Object scheduledTask) {}

    public DefaultHomeService(Plugin plugin, HomeRepository repository, HomeCache cache, Supplier<HomeConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public CompletableFuture<HomeResultStatus> setHome(Player player, String homeName, boolean force) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");

        HomeConfig config = configSupplier.get();
        Location loc = player.getLocation();
        String worldName = loc.getWorld().getName();

        if (isWorldRestricted(worldName, config)) {
            return CompletableFuture.completedFuture(HomeResultStatus.WORLD_RESTRICTED);
        }

        UUID uuid = player.getUniqueId();
        boolean exists = cache.getHome(uuid, homeName).isPresent();

        if (!exists && cache.getHomeCount(uuid) >= getMaxHomeLimit(player)) {
            return CompletableFuture.completedFuture(HomeResultStatus.LIMIT_REACHED);
        }

        if (exists && !force && config.requireOverwriteConfirmation()) {
            return CompletableFuture.completedFuture(HomeResultStatus.OVERWRITE_REQUIRED);
        }

        Set<UUID> sharedWith = cache.getHome(uuid, homeName)
            .map(Home::sharedWith)
            .orElse(Collections.emptySet());

        Home home = new Home(
            uuid,
            homeName,
            worldName,
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            loc.getYaw(),
            loc.getPitch(),
            System.currentTimeMillis(),
            sharedWith
        );

        return repository.save(home)
            .thenApply(v -> {
                cache.putHome(uuid, home);
                return HomeResultStatus.SUCCESS;
            })
            .exceptionally(ex -> HomeResultStatus.ERROR);
    }

    @Override
    public CompletableFuture<HomeResultStatus> deleteHome(Player player, String homeName) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");

        UUID uuid = player.getUniqueId();
        if (cache.getHome(uuid, homeName).isEmpty()) {
            return CompletableFuture.completedFuture(HomeResultStatus.HOME_NOT_FOUND);
        }

        return repository.delete(uuid, homeName)
            .thenApply(v -> {
                cache.removeHome(uuid, homeName);
                return HomeResultStatus.SUCCESS;
            })
            .exceptionally(ex -> HomeResultStatus.ERROR);
    }

    @Override
    public CompletableFuture<HomeResultStatus> setHomeOther(Player admin, UUID targetUuid, String homeName, Location location) {
        Objects.requireNonNull(admin, "admin cannot be null");
        Objects.requireNonNull(targetUuid, "targetUuid cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");
        Objects.requireNonNull(location, "location cannot be null");

        Home home = new Home(
            targetUuid,
            homeName,
            location.getWorld().getName(),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch(),
            System.currentTimeMillis(),
            Collections.emptySet()
        );

        return repository.save(home).thenApply(v -> {
            if (cache.isLoaded(targetUuid)) {
                cache.putHome(targetUuid, home);
            }
            return HomeResultStatus.SUCCESS;
        }).exceptionally(ex -> HomeResultStatus.ERROR);
    }

    @Override
    public CompletableFuture<HomeResultStatus> deleteHomeOther(Player admin, UUID targetUuid, String homeName) {
        Objects.requireNonNull(admin, "admin cannot be null");
        Objects.requireNonNull(targetUuid, "targetUuid cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");

        return repository.delete(targetUuid, homeName).thenApply(v -> {
            if (cache.isLoaded(targetUuid)) {
                cache.removeHome(targetUuid, homeName);
            }
            return HomeResultStatus.SUCCESS;
        }).exceptionally(ex -> HomeResultStatus.ERROR);
    }

    @Override
    public CompletableFuture<HomeResultStatus> shareHome(Player player, String homeName, UUID targetUuid) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");
        Objects.requireNonNull(targetUuid, "targetUuid cannot be null");

        HomeConfig config = configSupplier.get();
        if (!config.enableHomeSharing()) {
            return CompletableFuture.completedFuture(HomeResultStatus.ERROR);
        }

        UUID uuid = player.getUniqueId();
        Optional<Home> opt = cache.getHome(uuid, homeName);
        if (opt.isEmpty()) {
            return CompletableFuture.completedFuture(HomeResultStatus.HOME_NOT_FOUND);
        }

        Home home = opt.get();
        if (home.isSharedWith(targetUuid)) {
            return CompletableFuture.completedFuture(HomeResultStatus.ALREADY_SHARED);
        }

        Set<UUID> newShared = new HashSet<>(home.sharedWith());
        newShared.add(targetUuid);
        Home updatedHome = home.withSharedWith(newShared);

        return repository.save(updatedHome).thenApply(v -> {
            cache.putHome(uuid, updatedHome);
            return HomeResultStatus.SUCCESS;
        }).exceptionally(ex -> HomeResultStatus.ERROR);
    }

    @Override
    public CompletableFuture<HomeResultStatus> unshareHome(Player player, String homeName, UUID targetUuid) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");
        Objects.requireNonNull(targetUuid, "targetUuid cannot be null");

        HomeConfig config = configSupplier.get();
        if (!config.enableHomeSharing()) {
            return CompletableFuture.completedFuture(HomeResultStatus.ERROR);
        }

        UUID uuid = player.getUniqueId();
        Optional<Home> opt = cache.getHome(uuid, homeName);
        if (opt.isEmpty()) {
            return CompletableFuture.completedFuture(HomeResultStatus.HOME_NOT_FOUND);
        }

        Home home = opt.get();
        if (!home.isSharedWith(targetUuid)) {
            return CompletableFuture.completedFuture(HomeResultStatus.NOT_SHARED);
        }

        Set<UUID> newShared = new HashSet<>(home.sharedWith());
        newShared.remove(targetUuid);
        Home updatedHome = home.withSharedWith(newShared);

        return repository.save(updatedHome).thenApply(v -> {
            cache.putHome(uuid, updatedHome);
            return HomeResultStatus.SUCCESS;
        }).exceptionally(ex -> HomeResultStatus.ERROR);
    }

    @Override
    public CompletableFuture<Boolean> teleportHome(Player player, String homeName) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");

        UUID uuid = player.getUniqueId();
        Optional<Home> optHome = cache.getHome(uuid, homeName);
        if (optHome.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        return executeTeleport(player, optHome.get());
    }

    @Override
    public CompletableFuture<Boolean> teleportHomeOther(Player player, UUID targetUuid, String homeName) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(targetUuid, "targetUuid cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");

        return repository.findByName(targetUuid, homeName).thenCompose(optHome -> {
            if (optHome.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            return executeTeleport(player, optHome.get());
        });
    }

    @Override
    public CompletableFuture<Boolean> teleportSharedHome(Player player, UUID ownerUuid, String homeName) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(ownerUuid, "ownerUuid cannot be null");
        Objects.requireNonNull(homeName, "homeName cannot be null");

        HomeConfig config = configSupplier.get();
        if (!config.enableHomeSharing()) {
            return CompletableFuture.completedFuture(false);
        }

        return repository.findByName(ownerUuid, homeName).thenCompose(optHome -> {
            if (optHome.isEmpty() || !optHome.get().isSharedWith(player.getUniqueId())) {
                return CompletableFuture.completedFuture(false);
            }
            return executeTeleport(player, optHome.get());
        });
    }

    @Override
    public Optional<Home> getHome(UUID ownerUuid, String homeName) {
        if (ownerUuid == null || homeName == null) {
            return Optional.empty();
        }
        return cache.getHome(ownerUuid, homeName);
    }

    @Override
    public Map<String, Home> getHomes(UUID ownerUuid) {
        if (ownerUuid == null) {
            return Collections.emptyMap();
        }
        return cache.getHomes(ownerUuid);
    }

    @Override
    public int getMaxHomeLimit(Player player) {
        Objects.requireNonNull(player, "player cannot be null");
        if (player.hasPermission(Permissions.HOME_BYPASS_LIMIT)) {
            return Integer.MAX_VALUE;
        }

        HomeConfig config = configSupplier.get();
        int maxLimit = config.homeLimits().getOrDefault("default", 3);

        for (Map.Entry<String, Integer> entry : config.homeLimits().entrySet()) {
            if (player.hasPermission("tpcore.homes.limit." + entry.getKey())) {
                maxLimit = Math.max(maxLimit, entry.getValue());
            }
        }

        for (org.bukkit.permissions.PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            String perm = pai.getPermission().toLowerCase();
            if (perm.startsWith("tpcore.homes.limit.") && pai.getValue()) {
                String sub = perm.substring("tpcore.homes.limit.".length());
                try {
                    int val = Integer.parseInt(sub);
                    maxLimit = Math.max(maxLimit, val);
                } catch (NumberFormatException ignored) {}
            }
        }
        return maxLimit;
    }

    @Override
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

    public long getRemainingCooldownSeconds(Player player) {
        UUID uuid = player.getUniqueId();
        Long expiresAt = cooldownMap.get(uuid);
        if (expiresAt == null) return 0;

        long diff = expiresAt - System.currentTimeMillis();
        return diff > 0 ? (diff / 1000L) + 1 : 0;
    }

    private CompletableFuture<Boolean> executeTeleport(Player player, Home home) {
        UUID uuid = player.getUniqueId();
        HomeConfig config = configSupplier.get();

        if (!player.hasPermission(Permissions.HOME_BYPASS_COOLDOWN)) {
            long remainingSec = getRemainingCooldownSeconds(player);
            if (remainingSec > 0) {
                return CompletableFuture.completedFuture(false);
            }
        }

        World world = Bukkit.getWorld(home.worldName());
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }

        Location target = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        if (!isLocationSafe(target)) {
            return CompletableFuture.completedFuture(false);
        }

        int warmup = config.warmupSeconds();
        int cooldown = config.cooldownSeconds();

        // Cancel any existing active warmup for this player before creating a new one
        ActiveWarmup existing = activeWarmups.remove(uuid);
        if (existing != null) {
            cancelScheduledTask(existing.scheduledTask());
            existing.future().complete(false);
        }

        if (warmup <= 0 || player.hasPermission(Permissions.HOME_BYPASS_WARMUP)) {
            if (cooldown > 0 && !player.hasPermission(Permissions.HOME_BYPASS_COOLDOWN)) {
                cooldownMap.put(uuid, System.currentTimeMillis() + (cooldown * 1000L));
            }
            return player.teleportAsync(target);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Object task = schedulePlayerTask(player, warmup * 20L, () -> {
            activeWarmups.remove(uuid);
            if (cooldown > 0 && !player.hasPermission(Permissions.HOME_BYPASS_COOLDOWN)) {
                cooldownMap.put(uuid, System.currentTimeMillis() + (cooldown * 1000L));
            }
            player.teleportAsync(target).thenAccept(future::complete);
        });

        activeWarmups.put(uuid, new ActiveWarmup(player.getLocation().clone(), future, task));
        return future;
    }

    private Object schedulePlayerTask(Player player, long delayTicks, Runnable runnable) {
        try {
            return player.getScheduler().runDelayed(plugin, task -> runnable.run(), null, delayTicks);
        } catch (NoSuchMethodError | Exception e) {
            int taskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, runnable, delayTicks);
            return Integer.valueOf(taskId);
        }
    }

    private void cancelScheduledTask(Object task) {
        if (task == null) return;
        if (task instanceof Integer taskId) {
            Bukkit.getScheduler().cancelTask(taskId);
        } else {
            try {
                if (task instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask st) {
                    st.cancel();
                }
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

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        HomeConfig config = configSupplier.get();
        if (!config.cancelOnMove()) {
            return;
        }
        Player player = event.getPlayer();
        ActiveWarmup warmup = activeWarmups.get(player.getUniqueId());
        if (warmup != null) {
            Location from = warmup.startLocation();
            Location to = event.getTo();
            if (from.getWorld() != to.getWorld() || from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
                activeWarmups.remove(player.getUniqueId());
                cancelScheduledTask(warmup.scheduledTask());
                warmup.future().complete(false);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        HomeConfig config = configSupplier.get();
        if (!config.cancelOnDamage() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        ActiveWarmup warmup = activeWarmups.remove(player.getUniqueId());
        if (warmup != null) {
            cancelScheduledTask(warmup.scheduledTask());
            warmup.future().complete(false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cooldownMap.remove(uuid);
        ActiveWarmup warmup = activeWarmups.remove(uuid);
        if (warmup != null) {
            cancelScheduledTask(warmup.scheduledTask());
            warmup.future().complete(false);
        }
    }
}
