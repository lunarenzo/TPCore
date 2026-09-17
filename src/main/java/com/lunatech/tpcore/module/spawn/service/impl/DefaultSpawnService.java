package com.lunatech.tpcore.module.spawn.service.impl;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.spawn.model.SpawnLocation;
import com.lunatech.tpcore.module.spawn.repository.SpawnRepository;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultSpawnService implements SpawnService {

    private final JavaPlugin plugin;
    private final SpawnRepository repository;
    private final SpawnConfig config;
    private final MiniMessage miniMessage;

    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();
    private final Set<UUID> pendingVoidRescues = ConcurrentHashMap.newKeySet();
    private final Object2LongOpenHashMap<UUID> cooldowns = new Object2LongOpenHashMap<>();
    private final Object cooldownLock = new Object();

    private record ActiveWarmup(
        UUID playerId,
        Location startLocation,
        ScheduledTask task
    ) {}

    public DefaultSpawnService(JavaPlugin plugin, SpawnRepository repository, SpawnConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
        this.miniMessage = MiniMessage.miniMessage();
        this.repository.load();
    }

    @Override
    public Optional<Location> getEffectiveSpawnLocation(String worldName) {
        if (worldName != null && !worldName.isBlank()) {
            Optional<SpawnLocation> worldSpawn = this.repository.getWorldSpawn(worldName);
            if (worldSpawn.isPresent()) {
                Location loc = worldSpawn.get().toBukkit();
                if (loc != null) {
                    return Optional.of(loc);
                }
            }
        }

        Optional<SpawnLocation> globalSpawn = this.repository.getGlobalSpawn();
        if (globalSpawn.isPresent()) {
            Location loc = globalSpawn.get().toBukkit();
            if (loc != null) {
                return Optional.of(loc);
            }
        }

        // Fallback to vanilla world spawn if world is valid
        if (worldName != null && !worldName.isBlank()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                return Optional.of(world.getSpawnLocation());
            }
        }

        return Optional.empty();
    }

    @Override
    public void teleportToSpawn(Player player, String optionalWorldName) {
        String targetWorld = (optionalWorldName != null && !optionalWorldName.isBlank())
            ? optionalWorldName
            : player.getWorld().getName();

        Optional<Location> spawnLocOpt = this.getEffectiveSpawnLocation(targetWorld);
        if (spawnLocOpt.isEmpty()) {
            if (optionalWorldName != null && !optionalWorldName.isBlank()) {
                this.sendMessage(
                    player,
                    this.config.messages().noSpawnSetWorld(),
                    Placeholder.unparsed("world", optionalWorldName)
                );
            } else {
                this.sendMessage(player, this.config.messages().noSpawnSet());
            }
            return;
        }

        Location spawnLocation = spawnLocOpt.get();

        if (!player.hasPermission(Permissions.SPAWN_BYPASS)) {
            long remainingMs = this.getRemainingCooldownMs(player.getUniqueId());
            if (remainingMs > 0) {
                long remainingSeconds = (remainingMs + 999L) / 1000L;
                this.sendMessage(
                    player,
                    this.config.messages().cooldownActive(),
                    Placeholder.unparsed("seconds", String.valueOf(remainingSeconds))
                );
                return;
            }
        }

        int warmupSeconds = this.config.warmupSeconds();
        if (warmupSeconds <= 0 || player.hasPermission(Permissions.SPAWN_BYPASS)) {
            this.executeTeleport(player, spawnLocation);
            return;
        }

        this.cancelWarmup(player.getUniqueId(), null);

        this.sendMessage(
            player,
            this.config.messages().warmupStart(),
            Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
        );

        ScheduledTask task = player.getScheduler().runDelayed(
            this.plugin,
            scheduledTask -> {
                ActiveWarmup warmup = this.activeWarmups.remove(player.getUniqueId());
                if (warmup != null && player.isOnline()) {
                    this.executeTeleport(player, spawnLocation);
                }
            },
            null,
            warmupSeconds * 20L
        );

        if (task != null) {
            this.activeWarmups.put(
                player.getUniqueId(),
                new ActiveWarmup(player.getUniqueId(), player.getLocation().clone(), task)
            );
        }
    }

    private void executeTeleport(Player player, Location targetLocation) {
        synchronized (this.cooldownLock) {
            this.cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        }
        player.teleportAsync(targetLocation);
        this.sendMessage(player, this.config.messages().spawnTeleportSuccess());
    }

    private long getRemainingCooldownMs(UUID playerId) {
        long lastTime;
        synchronized (this.cooldownLock) {
            lastTime = this.cooldowns.getOrDefault(playerId, 0L);
        }
        if (lastTime <= 0) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - lastTime;
        long cooldownMs = this.config.cooldownSeconds() * 1000L;
        return Math.max(0L, cooldownMs - elapsed);
    }

    @Override
    public void setGlobalSpawn(Player player) {
        SpawnLocation spawnLoc = SpawnLocation.fromBukkit(player.getLocation());
        this.repository.setGlobalSpawn(spawnLoc);
        this.sendMessage(
            player,
            this.config.messages().setSpawnGlobalSuccess(),
            Placeholder.unparsed("location", this.formatLocation(player.getLocation()))
        );
    }

    @Override
    public void setWorldSpawn(Player player, String worldName) {
        String targetWorld = (worldName != null && !worldName.isBlank()) ? worldName : player.getWorld().getName();
        SpawnLocation spawnLoc = SpawnLocation.fromBukkit(player.getLocation());
        this.repository.setWorldSpawn(targetWorld, spawnLoc);
        this.sendMessage(
            player,
            this.config.messages().setSpawnWorldSuccess(),
            Placeholder.unparsed("world", targetWorld),
            Placeholder.unparsed("location", this.formatLocation(player.getLocation()))
        );
    }

    @Override
    public void deleteGlobalSpawn(Player player) {
        this.repository.removeGlobalSpawn();
        this.sendMessage(player, this.config.messages().delSpawnGlobalSuccess());
    }

    @Override
    public void deleteWorldSpawn(Player player, String worldName) {
        String targetWorld = (worldName != null && !worldName.isBlank()) ? worldName : player.getWorld().getName();
        this.repository.removeWorldSpawn(targetWorld);
        this.sendMessage(
            player,
            this.config.messages().delSpawnWorldSuccess(),
            Placeholder.unparsed("world", targetWorld)
        );
    }

    @Override
    public void handlePlayerMove(Player player) {
        if (!this.config.cancelOnMove() || this.activeWarmups.isEmpty()) {
            return;
        }
        ActiveWarmup warmup = this.activeWarmups.get(player.getUniqueId());
        if (warmup != null) {
            Location start = warmup.startLocation();
            Location current = player.getLocation();

            if (start.getWorld() == null || !start.getWorld().equals(current.getWorld())
                || start.distanceSquared(current) > 0.25) {
                this.cancelWarmup(player.getUniqueId(), this.config.messages().warmupCancelledMove());
            }
        }
    }

    @Override
    public void handlePlayerDamage(UUID playerId) {
        if (this.config.cancelOnDamage()) {
            this.cancelWarmup(playerId, this.config.messages().warmupCancelledDamage());
        }
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        this.cancelWarmup(playerId, null);
        this.pendingVoidRescues.remove(playerId);
        synchronized (this.cooldownLock) {
            this.cooldowns.remove(playerId);
        }
    }

    @Override
    public void rescueFromVoid(Player player) {
        if (!this.config.voidFallProtection() || !this.pendingVoidRescues.add(player.getUniqueId())) {
            return;
        }

        player.setFallDistance(0.0f);
        Location dest = this.getEffectiveSpawnLocation(player.getWorld().getName())
            .orElseGet(() -> player.getWorld().getSpawnLocation());

        this.sendMessage(player, this.config.messages().voidRescued());

        player.teleportAsync(dest).thenAccept(success -> {
            player.getScheduler().runDelayed(this.plugin, task -> {
                this.pendingVoidRescues.remove(player.getUniqueId());
            }, null, 40L);
        });
    }

    private void cancelWarmup(UUID playerId, String cancelMessageTemplate) {
        ActiveWarmup warmup = this.activeWarmups.remove(playerId);
        if (warmup != null) {
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
            if (cancelMessageTemplate != null) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    this.sendMessage(player, cancelMessageTemplate);
                }
            }
        }
    }

    private String formatLocation(Location loc) {
        String worldName = (loc.getWorld() != null) ? loc.getWorld().getName() : "unknown";
        return String.format("%s (%.1f, %.1f, %.1f)", worldName, loc.getX(), loc.getY(), loc.getZ());
    }

    private void sendMessage(Player player, String template, TagResolver... resolvers) {
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config.messages().prefix());
        TagResolver combined = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));
        player.sendMessage(this.miniMessage.deserialize(template, combined));
    }

    @Override
    public void shutdown() {
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
        }
        this.activeWarmups.clear();
        this.pendingVoidRescues.clear();
        synchronized (this.cooldownLock) {
            this.cooldowns.clear();
        }
    }
}
