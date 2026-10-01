package com.lunatech.tpcore.module.spawn.service.impl;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.spawn.model.SpawnLocation;
import com.lunatech.tpcore.module.spawn.repository.SpawnRepository;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultSpawnService implements SpawnService {

    private final JavaPlugin plugin;
    private final SpawnRepository repository;
    private final AtomicReference<SpawnConfig> configRef;
    private final MiniMessage miniMessage;

    private final SpawnWarmupManager warmupManager;
    private final SpawnCooldownManager cooldownManager;
    private final Set<UUID> pendingVoidRescues = ConcurrentHashMap.newKeySet();

    public DefaultSpawnService(JavaPlugin plugin, SpawnRepository repository, SpawnConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.configRef = new AtomicReference<>(config);
        this.miniMessage = MiniMessage.miniMessage();
        this.warmupManager = new SpawnWarmupManager(plugin);
        this.cooldownManager = new SpawnCooldownManager();
        this.repository.load();
    }

    private SpawnConfig config() {
        return this.configRef.get();
    }

    @Override
    public void updateConfig(SpawnConfig newConfig) {
        if (newConfig != null) {
            this.configRef.set(newConfig);
        }
    }

    @Override
    public Optional<Location> getEffectiveSpawnLocation(String worldName) {
        if (worldName != null && !worldName.isBlank()) {
            Optional<SpawnLocation> worldSpawn = this.repository.getWorldSpawn(worldName);
            if (worldSpawn.isPresent()) {
                Location loc = worldSpawn.get().toBukkit();
                if (loc != null && loc.getWorld() != null) {
                    return Optional.of(loc);
                }
            }
        }

        Optional<SpawnLocation> globalSpawn = this.repository.getGlobalSpawn();
        if (globalSpawn.isPresent()) {
            Location loc = globalSpawn.get().toBukkit();
            if (loc != null && loc.getWorld() != null) {
                return Optional.of(loc);
            }
        }

        if (worldName != null && !worldName.isBlank()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                return Optional.of(world.getSpawnLocation());
            }
        }

        if (!Bukkit.getWorlds().isEmpty()) {
            return Optional.of(Bukkit.getWorlds().get(0).getSpawnLocation());
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
                    this.config().messages().noSpawnSetWorld(),
                    Placeholder.unparsed("world", optionalWorldName)
                );
            } else {
                this.sendMessage(player, this.config().messages().noSpawnSet());
            }
            return;
        }

        Location targetLoc = spawnLocOpt.get();

        if (!player.hasPermission(Permissions.SPAWN_BYPASS)) {
            long remainingMs = this.cooldownManager.getRemainingCooldownMs(player.getUniqueId(), this.config().cooldownSeconds());
            if (remainingMs > 0) {
                long remainingSeconds = (remainingMs + 999L) / 1000L;
                this.sendMessage(
                    player,
                    this.config().messages().cooldownActive(),
                    Placeholder.unparsed("seconds", String.valueOf(remainingSeconds))
                );
                return;
            }
        }

        int warmupSeconds = this.config().warmupSeconds();
        if (warmupSeconds <= 0 || player.hasPermission(Permissions.SPAWN_BYPASS)) {
            this.executeTeleport(player, targetLoc);
            return;
        }

        this.warmupManager.cancelWarmup(player.getUniqueId(), null);

        this.sendMessage(
            player,
            this.config().messages().warmupStart(),
            Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
        );

        this.warmupManager.startWarmup(player, warmupSeconds, () -> this.executeTeleport(player, targetLoc));
    }

    private void executeTeleport(Player player, Location targetLocation) {
        Location finalLocation = targetLocation;
        if (this.config().requireSafeLocation()) {
            Location safe = SpawnSafetyInspector.findSafeLocation(targetLocation);
            if (safe != null) {
                finalLocation = safe;
            }
        }

        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }

        player.teleportAsync(finalLocation).thenAccept(success -> {
            player.getScheduler().run(this.plugin, task -> {
                if (Boolean.TRUE.equals(success)) {
                    if (!player.hasPermission(Permissions.SPAWN_BYPASS)) {
                        this.cooldownManager.applyCooldown(player.getUniqueId());
                    }
                    this.sendMessage(player, this.config().messages().spawnTeleportSuccess());
                } else {
                    this.sendMessage(player, this.config().messages().teleportFailed());
                }
            }, null);
        });
    }

    @Override
    public void setGlobalSpawn(Player player) {
        SpawnLocation spawnLoc = SpawnLocation.fromBukkit(player.getLocation());
        this.repository.setGlobalSpawn(spawnLoc);
        this.sendMessage(
            player,
            this.config().messages().setSpawnGlobalSuccess(),
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
            this.config().messages().setSpawnWorldSuccess(),
            Placeholder.unparsed("world", targetWorld),
            Placeholder.unparsed("location", this.formatLocation(player.getLocation()))
        );
    }

    @Override
    public void deleteGlobalSpawn(Player player) {
        this.repository.removeGlobalSpawn();
        this.sendMessage(player, this.config().messages().delSpawnGlobalSuccess());
    }

    @Override
    public void deleteWorldSpawn(Player player, String worldName) {
        String targetWorld = (worldName != null && !worldName.isBlank()) ? worldName : player.getWorld().getName();
        this.repository.removeWorldSpawn(targetWorld);
        this.sendMessage(
            player,
            this.config().messages().delSpawnWorldSuccess(),
            Placeholder.unparsed("world", targetWorld)
        );
    }

    @Override
    public void handlePlayerMove(Player player) {
        if (!this.config().cancelOnMove()) {
            return;
        }
        this.warmupManager.checkMovement(
            player,
            p -> this.sendMessage(p, this.config().messages().warmupCancelledMove())
        );
    }

    @Override
    public void handlePlayerDamage(UUID playerId) {
        if (!this.config().cancelOnDamage()) {
            return;
        }
        this.warmupManager.cancelWarmup(
            playerId,
            p -> this.sendMessage(p, this.config().messages().warmupCancelledDamage())
        );
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        this.warmupManager.handleQuit(playerId);
        this.cooldownManager.removeCooldown(playerId);
        this.pendingVoidRescues.remove(playerId);
    }

    @Override
    public void rescueFromVoid(Player player) {
        if (!this.config().voidFallProtection() || !this.pendingVoidRescues.add(player.getUniqueId())) {
            return;
        }

        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0f);

        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }

        Location dest = this.getEffectiveSpawnLocation(player.getWorld().getName())
            .orElseGet(() -> player.getWorld().getSpawnLocation());

        if (this.config().requireSafeLocation()) {
            Location safe = SpawnSafetyInspector.findSafeLocation(dest);
            if (safe != null) {
                dest = safe;
            }
        }

        Location finalDest = dest;
        player.teleportAsync(finalDest).thenAccept(success -> {
            player.getScheduler().run(this.plugin, task -> {
                if (Boolean.TRUE.equals(success)) {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                    this.sendMessage(player, this.config().messages().voidRescued());
                }
                player.getScheduler().runDelayed(this.plugin, delayTask -> {
                    this.pendingVoidRescues.remove(player.getUniqueId());
                }, null, 20L);
            }, null);
        });
    }

    private String formatLocation(Location loc) {
        String worldName = (loc.getWorld() != null) ? loc.getWorld().getName() : "unknown";
        return String.format("%s (%.1f, %.1f, %.1f)", worldName, loc.getX(), loc.getY(), loc.getZ());
    }

    private void sendMessage(Player player, String template, TagResolver... resolvers) {
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        TagResolver combined = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));
        player.sendMessage(this.miniMessage.deserialize(template, combined));
    }

    @Override
    public void shutdown() {
        this.warmupManager.cancelAll();
        this.cooldownManager.clear();
        this.pendingVoidRescues.clear();
    }
}
