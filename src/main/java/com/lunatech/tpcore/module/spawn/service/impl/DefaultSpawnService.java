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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
                if (loc != null && loc.getWorld() != null) return Optional.of(loc);
            }
        }
        Optional<SpawnLocation> globalSpawn = this.repository.getGlobalSpawn();
        if (globalSpawn.isPresent()) {
            Location loc = globalSpawn.get().toBukkit();
            if (loc != null && loc.getWorld() != null) return Optional.of(loc);
        }
        if (worldName != null && !worldName.isBlank()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) return Optional.of(world.getSpawnLocation());
        }
        if (!Bukkit.getWorlds().isEmpty()) {
            return Optional.of(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        return Optional.empty();
    }

    @Override
    public void teleportToSpawn(Player player, String optionalWorldName) {
        String targetWorld = (optionalWorldName != null && !optionalWorldName.isBlank()) ? optionalWorldName : player.getWorld().getName();
        Optional<Location> spawnLocOpt = this.getEffectiveSpawnLocation(targetWorld);
        if (spawnLocOpt.isEmpty()) {
            String msg = (optionalWorldName != null && !optionalWorldName.isBlank()) ? this.config().messages().noSpawnSetWorld() : this.config().messages().noSpawnSet();
            this.sendMessage(player, msg, Placeholder.unparsed("world", targetWorld));
            return;
        }

        Location targetLoc = spawnLocOpt.get();
        boolean bypassCooldown = player.hasPermission(Permissions.SPAWN_BYPASS) || player.hasPermission(Permissions.SPAWN_BYPASS_COOLDOWN);
        if (!bypassCooldown) {
            long remainingMs = this.cooldownManager.getRemainingCooldownMs(player.getUniqueId(), this.config().cooldownSeconds());
            if (remainingMs > 0) {
                long remainingSeconds = (remainingMs + 999L) / 1000L;
                this.sendMessage(player, this.config().messages().cooldownActive(), Placeholder.unparsed("seconds", String.valueOf(remainingSeconds)));
                return;
            }
        }

        if (this.spawnTeleportsInProgress.contains(player.getUniqueId())) return;

        boolean isChargeOnSuccess = "CHARGE_ON_SUCCESS".equals(this.config().getNormalizedChargeTiming());
        var fundCheckFuture = isChargeOnSuccess ? this.economyService.validateFundsAsync(player) : this.economyService.processTeleportCostAsync(player);

        fundCheckFuture.thenAccept(canProceed -> {
            if (!Boolean.TRUE.equals(canProceed)) return;
            player.getScheduler().run(this.plugin, task -> {
                if (!player.isOnline() || this.spawnTeleportsInProgress.contains(player.getUniqueId())) return;
                double cost = this.economyService.getCost(player);
                double paidCost = isChargeOnSuccess ? 0.0 : cost;
                boolean bypassWarmup = player.hasPermission(Permissions.SPAWN_BYPASS) || player.hasPermission(Permissions.SPAWN_BYPASS_WARMUP);
                int warmupSeconds = this.config().warmupSeconds();
                if (warmupSeconds <= 0 || bypassWarmup) {
                    this.executeTeleport(player, targetLoc, paidCost, isChargeOnSuccess);
                    return;
                }
                this.warmupManager.cancelWarmup(player.getUniqueId(), null);
                this.sendMessage(player, this.config().messages().warmupStart(), Placeholder.unparsed("seconds", String.valueOf(warmupSeconds)));
                this.warmupManager.startWarmup(player, warmupSeconds, paidCost, () -> this.executeTeleport(player, targetLoc, paidCost, isChargeOnSuccess));
            }, null);
        });
    }

    private void preparePlayerForTeleport(Player player) {
        if (player.isInsideVehicle()) player.leaveVehicle();
        if (!player.getPassengers().isEmpty()) player.eject();
    }

    private void dispatchTeleport(Player target, Location targetLoc, Runnable onFail, Consumer<Location> onResolved) {
        this.preparePlayerForTeleport(target);
        UUID targetId = target.getUniqueId();
        if (!this.spawnTeleportsInProgress.add(targetId)) return;

        if (this.config().requireSafeLocation()) {
            SpawnSafetyInspector.findSafeLocationAsync(this.plugin, targetLoc).thenAccept(safeLoc -> {
                target.getScheduler().run(this.plugin, task -> {
                    if (!target.isOnline() || safeLoc == null) {
                        this.spawnTeleportsInProgress.remove(targetId);
                        if (onFail != null) onFail.run();
                        return;
                    }
                    onResolved.accept(safeLoc);
                }, null);
            });
        } else {
            onResolved.accept(targetLoc);
        }
    }

    private void executeTeleport(Player player, Location targetLocation, double paidCost, boolean isChargeOnSuccess) {
        this.dispatchTeleport(player, targetLocation, () -> {
            this.sendMessage(player, this.config().messages().teleportFailed());
            if (paidCost > 0.0 && this.config().refundOnCancel()) this.economyService.processRefund(player, paidCost);
        }, safeLoc -> this.performFinalTeleport(null, player, safeLoc, paidCost, isChargeOnSuccess, false));
    }

    @Override
    public void teleportOtherToSpawn(CommandSender sender, Player target, String optionalWorldName) {
        if (target == null || !target.isOnline()) {
            if (sender != null) {
                String name = (target != null) ? target.getName() : "unknown";
                this.sendSenderMessage(sender, this.config().messages().playerNotOnline(), Placeholder.unparsed("player", name));
            }
            return;
        }

        String targetWorld = (optionalWorldName != null && !optionalWorldName.isBlank()) ? optionalWorldName : target.getWorld().getName();
        Optional<Location> spawnLocOpt = this.getEffectiveSpawnLocation(targetWorld);
        if (spawnLocOpt.isEmpty()) {
            if (sender != null) this.sendSenderMessage(sender, this.config().messages().noSpawnSet());
            return;
        }

        this.warmupManager.cancelWarmup(target.getUniqueId(), null);
        this.dispatchTeleport(target, spawnLocOpt.get(), () -> {
            if (sender != null) this.sendSenderMessage(sender, this.config().messages().teleportFailed());
        }, safeLoc -> this.performFinalTeleport(sender, target, safeLoc, 0.0, false, true));
    }

    private void performFinalTeleport(CommandSender sender, Player player, Location destination, double paidCost, boolean isChargeOnSuccess, boolean isOther) {
        UUID playerId = player.getUniqueId();
        this.spawnTeleportsInProgress.add(playerId);

        player.teleportAsync(destination).whenComplete((success, ex) -> {
            player.getScheduler().run(this.plugin, task -> {
                this.spawnTeleportsInProgress.remove(playerId);
                if (Boolean.TRUE.equals(success) && ex == null) {
                    if (!isOther) {
                        boolean bypassCooldown = player.hasPermission(Permissions.SPAWN_BYPASS) || player.hasPermission(Permissions.SPAWN_BYPASS_COOLDOWN);
                        if (!bypassCooldown) this.cooldownManager.applyCooldown(playerId);
                        if (isChargeOnSuccess) this.economyService.chargeSuccessAsync(player);
                        this.sendMessage(player, this.config().messages().spawnTeleportSuccess());
                    } else {
                        this.sendMessage(player, this.config().messages().teleportOtherTarget());
                        if (sender != null && !sender.equals(player)) {
                            this.sendSenderMessage(sender, this.config().messages().teleportOtherSuccess(), Placeholder.unparsed("player", player.getName()));
                        }
                    }
                    this.protectionManager.grantTeleportProtection(player);
                } else {
                    if (paidCost > 0.0 && this.config().refundOnCancel()) this.economyService.processRefund(player, paidCost);
                    if (isOther && sender != null) {
                        this.sendSenderMessage(sender, this.config().messages().teleportFailed());
                    } else {
                        this.sendMessage(player, this.config().messages().teleportFailed());
                    }
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
        if (!this.config().voidFallProtection() || !this.pendingVoidRescues.add(playerId)) return;

        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0f);
        Location dest = this.getEffectiveSpawnLocation(player.getWorld().getName()).orElseGet(() -> player.getWorld().getSpawnLocation());
        this.dispatchTeleport(player, dest, () -> this.performVoidTeleport(player, dest), safeLoc -> this.performVoidTeleport(player, safeLoc));
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
                    player.getScheduler().runDelayed(this.plugin, delayTask -> this.pendingVoidRescues.remove(playerId), null, 20L);
                }
            }, null);
        });
    }

    private String formatLocation(Location loc) {
        String worldName = (loc.getWorld() != null) ? loc.getWorld().getName() : "unknown";
        return String.format("%s (%.1f, %.1f, %.1f)", worldName, loc.getX(), loc.getY(), loc.getZ());
    }

    private void sendMessage(Player player, String template, TagResolver... resolvers) {
        this.sendSenderMessage(player, template, resolvers);
    }

    private void sendSenderMessage(CommandSender sender, String template, TagResolver... resolvers) {
        if (sender == null || template == null || template.isBlank()) return;
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(this.config().messages().prefix()));
        TagResolver combined = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));
        sender.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
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
