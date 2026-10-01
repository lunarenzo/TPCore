package com.lunatech.tpcore.module.spawn.service.impl;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.spawn.economy.SpawnEconomyService;
import com.lunatech.tpcore.module.spawn.model.SpawnLocation;
import com.lunatech.tpcore.module.spawn.repository.SpawnRepository;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import com.lunatech.tpcore.util.MessageFormatter;
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
    private final SpawnEconomyService economyService;
    private final AtomicReference<SpawnConfig> configRef;
    private final MiniMessage miniMessage;

    private final SpawnWarmupRenderer warmupRenderer;
    private final SpawnWarmupManager warmupManager;
    private final SpawnCooldownManager cooldownManager;
    private final SpawnProtectionManager protectionManager;
    private final Set<UUID> pendingVoidRescues = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spawnTeleportsInProgress = ConcurrentHashMap.newKeySet();

    public DefaultSpawnService(JavaPlugin plugin, SpawnRepository repository, SpawnEconomyService economyService, SpawnConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.economyService = economyService;
        this.configRef = new AtomicReference<>(config);
        this.miniMessage = MiniMessage.miniMessage();
        this.warmupRenderer = new SpawnWarmupRenderer(this.miniMessage);
        this.warmupManager = new SpawnWarmupManager(plugin, this.configRef::get, economyService, this.warmupRenderer);
        this.cooldownManager = new SpawnCooldownManager();
        this.protectionManager = new SpawnProtectionManager(plugin, this.configRef::get, this.miniMessage);
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
    public boolean hasActiveWarmup(UUID playerId) {
        return this.warmupManager.hasActiveWarmup(playerId);
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

        boolean bypassCooldown = player.hasPermission(Permissions.SPAWN_BYPASS)
            || player.hasPermission(Permissions.SPAWN_BYPASS_COOLDOWN);

        if (!bypassCooldown) {
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

        boolean isChargeOnSuccess = "CHARGE_ON_SUCCESS".equals(this.config().getNormalizedChargeTiming());
        var fundCheckFuture = isChargeOnSuccess
            ? this.economyService.validateFundsAsync(player)
            : this.economyService.processTeleportCostAsync(player);

        fundCheckFuture.thenAccept(canProceed -> {
            if (!Boolean.TRUE.equals(canProceed)) {
                return;
            }

            player.getScheduler().run(this.plugin, task -> {
                if (!player.isOnline()) {
                    return;
                }

                double cost = this.economyService.getCost(player);
                double paidCost = isChargeOnSuccess ? 0.0 : cost;

                boolean bypassWarmup = player.hasPermission(Permissions.SPAWN_BYPASS)
                    || player.hasPermission(Permissions.SPAWN_BYPASS_WARMUP);

                int warmupSeconds = this.config().warmupSeconds();
                if (warmupSeconds <= 0 || bypassWarmup) {
                    this.executeTeleport(player, targetLoc, paidCost, isChargeOnSuccess);
                    return;
                }

                this.warmupManager.cancelWarmup(player.getUniqueId(), null);

                this.sendMessage(
                    player,
                    this.config().messages().warmupStart(),
                    Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
                );

                this.warmupManager.startWarmup(player, warmupSeconds, paidCost, () -> this.executeTeleport(player, targetLoc, paidCost, isChargeOnSuccess));
            }, null);
        });
    }

    private void executeTeleport(Player player, Location targetLocation, double paidCost, boolean isChargeOnSuccess) {
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (!player.getPassengers().isEmpty()) {
            player.eject();
        }

        if (this.config().requireSafeLocation()) {
            SpawnSafetyInspector.findSafeLocationAsync(this.plugin, targetLocation).thenAccept(safeLoc -> {
                player.getScheduler().run(this.plugin, task -> {
                    if (!player.isOnline()) {
                        if (paidCost > 0.0 && this.config().refundOnCancel()) {
                            this.economyService.processRefund(player, paidCost);
                        }
                        return;
                    }
                    if (safeLoc == null) {
                        this.sendMessage(player, this.config().messages().teleportFailed());
                        if (paidCost > 0.0 && this.config().refundOnCancel()) {
                            this.economyService.processRefund(player, paidCost);
                        }
                        return;
                    }
                    this.performTeleport(player, safeLoc, paidCost, isChargeOnSuccess);
                }, null);
            });
        } else {
            this.performTeleport(player, targetLocation, paidCost, isChargeOnSuccess);
        }
    }

    private void performTeleport(Player player, Location destination, double paidCost, boolean isChargeOnSuccess) {
        UUID playerId = player.getUniqueId();
        this.spawnTeleportsInProgress.add(playerId);

        player.teleportAsync(destination).whenComplete((success, ex) -> {
            player.getScheduler().run(this.plugin, task -> {
                this.spawnTeleportsInProgress.remove(playerId);
                if (Boolean.TRUE.equals(success) && ex == null) {
                    boolean bypassCooldown = player.hasPermission(Permissions.SPAWN_BYPASS)
                        || player.hasPermission(Permissions.SPAWN_BYPASS_COOLDOWN);
                    if (!bypassCooldown) {
                        this.cooldownManager.applyCooldown(playerId);
                    }
                    if (isChargeOnSuccess) {
                        this.economyService.chargeSuccessAsync(player);
                    }
                    this.protectionManager.grantTeleportProtection(player);
                    this.sendMessage(player, this.config().messages().spawnTeleportSuccess());
                } else {
                    if (paidCost > 0.0 && this.config().refundOnCancel()) {
                        this.economyService.processRefund(player, paidCost);
                    }
                    this.sendMessage(player, this.config().messages().teleportFailed());
                }
            }, null);
        });
    }

    @Override
    public void setGlobalSpawn(Player player) {
        this.repository.setGlobalSpawn(SpawnLocation.fromBukkit(player.getLocation()));
        this.sendMessage(player, this.config().messages().setSpawnGlobalSuccess(), Placeholder.unparsed("location", this.formatLocation(player.getLocation())));
    }

    @Override
    public void setWorldSpawn(Player player, String worldName) {
        String targetWorld = (worldName != null && !worldName.isBlank()) ? worldName : player.getWorld().getName();
        this.repository.setWorldSpawn(targetWorld, SpawnLocation.fromBukkit(player.getLocation()));
        this.sendMessage(player, this.config().messages().setSpawnWorldSuccess(), Placeholder.unparsed("world", targetWorld), Placeholder.unparsed("location", this.formatLocation(player.getLocation())));
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
        this.sendMessage(player, this.config().messages().delSpawnWorldSuccess(), Placeholder.unparsed("world", targetWorld));
    }

    @Override
    public void handlePlayerMove(Player player) {
        if (this.config().cancelOnMove() && this.warmupManager.checkMovement(player)) {
            this.warmupManager.cancelWarmup(player.getUniqueId(), p -> this.sendMessage(p, this.config().messages().warmupCancelledMove()));
        }
    }

    @Override
    public void handlePlayerDamage(UUID playerId) {
        if (this.config().cancelOnDamage()) {
            this.warmupManager.cancelWarmup(playerId, p -> this.sendMessage(p, this.config().messages().warmupCancelledDamage()));
        }
    }

    @Override
    public void handlePlayerTeleport(UUID playerId) {
        if (!this.spawnTeleportsInProgress.contains(playerId)) {
            this.warmupManager.cancelWarmup(playerId, p -> this.sendMessage(p, this.config().messages().warmupCancelledTeleport()));
        }
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        this.warmupManager.handleQuit(playerId);
        this.cooldownManager.removeCooldown(playerId);
        this.protectionManager.evict(playerId);
        this.pendingVoidRescues.remove(playerId);
        this.spawnTeleportsInProgress.remove(playerId);
    }

    @Override
    public boolean handlePlayerProtectionDamage(Player victim, Player attacker, boolean isPvp) {
        return this.protectionManager.handlePlayerProtectionDamage(victim, attacker, isPvp);
    }

    @Override
    public long getTeleportProtectionStartTime(UUID playerId) {
        return this.protectionManager.getTeleportProtectionStartTime(playerId);
    }

    @Override
    public void rescueFromVoid(Player player) {
        UUID playerId = player.getUniqueId();
        if (!this.config().voidFallProtection() || !this.pendingVoidRescues.add(playerId)) {
            return;
        }

        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0f);

        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (!player.getPassengers().isEmpty()) {
            player.eject();
        }

        Location dest = this.getEffectiveSpawnLocation(player.getWorld().getName())
            .orElseGet(() -> player.getWorld().getSpawnLocation());

        if (this.config().requireSafeLocation()) {
            SpawnSafetyInspector.findSafeLocationAsync(this.plugin, dest).thenAccept(safeLoc -> {
                Location finalLoc = (safeLoc != null) ? safeLoc : dest;
                this.performVoidTeleport(player, finalLoc);
            });
        } else {
            this.performVoidTeleport(player, dest);
        }
    }

    private void performVoidTeleport(Player player, Location target) {
        UUID playerId = player.getUniqueId();
        this.spawnTeleportsInProgress.add(playerId);

        player.teleportAsync(target).whenComplete((success, ex) -> {
            player.getScheduler().run(this.plugin, task -> {
                this.spawnTeleportsInProgress.remove(playerId);
                try {
                    if (Boolean.TRUE.equals(success) && ex == null) {
                        player.setVelocity(new Vector(0, 0, 0));
                        player.setFallDistance(0.0f);
                        this.sendMessage(player, this.config().messages().voidRescued());
                    }
                } finally {
                    player.getScheduler().runDelayed(this.plugin, delayTask -> {
                        this.pendingVoidRescues.remove(playerId);
                    }, null, 20L);
                }
            }, null);
        });
    }

    private String formatLocation(Location loc) {
        String worldName = (loc.getWorld() != null) ? loc.getWorld().getName() : "unknown";
        return String.format("%s (%.1f, %.1f, %.1f)", worldName, loc.getX(), loc.getY(), loc.getZ());
    }

    private void sendMessage(Player player, String template, TagResolver... resolvers) {
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(this.config().messages().prefix()));
        TagResolver combined = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
    }

    @Override
    public void shutdown() {
        this.warmupManager.cancelAll();
        this.cooldownManager.clear();
        this.protectionManager.clear();
        this.pendingVoidRescues.clear();
        this.spawnTeleportsInProgress.clear();
        this.warmupRenderer.clear();
        this.economyService.shutdown();
    }
}
