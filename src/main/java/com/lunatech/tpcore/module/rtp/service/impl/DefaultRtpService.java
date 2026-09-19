package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpWorldConfig;
import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import com.lunatech.tpcore.module.rtp.service.RtpService;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Core implementation of RtpService providing O(1) candidate popping,
 * inner-ring chunk preloading, Folia-safe safety inspection, and teleportation.
 */
public final class DefaultRtpService implements RtpService {

    private final JavaPlugin plugin;
    private final Supplier<RtpConfig> configSupplier;
    private final AdaptiveRtpReplenisher replenisher;
    private final RtpSafetyInspector safetyInspector;
    private final RtpChunkTicketManager ticketManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private record WarmupSession(ScheduledTask task, CompletableFuture<Boolean> future) {}

    private final Map<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
    private final Map<UUID, WarmupSession> warmupTasks = new ConcurrentHashMap<>();

    public DefaultRtpService(
        JavaPlugin plugin,
        Supplier<RtpConfig> configSupplier,
        AdaptiveRtpReplenisher replenisher,
        RtpSafetyInspector safetyInspector,
        RtpChunkTicketManager ticketManager
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.replenisher = Objects.requireNonNull(replenisher, "replenisher cannot be null");
        this.safetyInspector = Objects.requireNonNull(safetyInspector, "safetyInspector cannot be null");
        this.ticketManager = Objects.requireNonNull(ticketManager, "ticketManager cannot be null");
    }

    @Override
    public CompletableFuture<Boolean> executeRtp(Player player) {
        return executeRtp(player, player.getWorld());
    }

    @Override
    public CompletableFuture<Boolean> executeRtp(Player player, World targetWorld) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        World world = targetWorld != null ? targetWorld : player.getWorld();
        RtpConfig config = this.configSupplier.get();

        if (!config.enabled()) {
            sendMessage(player, config.messages().disabled());
            return CompletableFuture.completedFuture(false);
        }

        if (!player.hasPermission(Permissions.RTP_USE)) {
            sendMessage(player, config.messages().noPermission());
            return CompletableFuture.completedFuture(false);
        }

        RtpWorldConfig worldConfig = config.worldConfigs().get(world.getName().toLowerCase());
        if (worldConfig == null || !worldConfig.enabled()) {
            sendMessage(player, config.messages().worldDisabled(), Placeholder.unparsed("world", world.getName()));
            return CompletableFuture.completedFuture(false);
        }

        // Check Cooldown
        UUID uuid = player.getUniqueId();
        if (!player.hasPermission(Permissions.RTP_BYPASS_COOLDOWN)) {
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

        // Check Warmup
        int warmupSeconds = worldConfig.warmupSeconds();
        if (warmupSeconds > 0 && !player.hasPermission(Permissions.RTP_BYPASS_WARMUP)) {
            if (isWarmingUp(uuid)) {
                sendMessage(player, config.messages().warmupAlreadyActive());
                return CompletableFuture.completedFuture(false);
            }

            sendMessage(
                player,
                config.messages().warmupStarted(),
                Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
            );

            CompletableFuture<Boolean> warmupFuture = new CompletableFuture<>();

            ScheduledTask task = player.getScheduler().runDelayed(
                this.plugin,
                t -> {
                    this.warmupTasks.remove(uuid);
                    dispatchTeleport(player, world, worldConfig, 0).thenAccept(warmupFuture::complete);
                },
                null,
                warmupSeconds * 20L
            );

            if (task != null) {
                this.warmupTasks.put(uuid, new WarmupSession(task, warmupFuture));
                return warmupFuture;
            }
        }

        return dispatchTeleport(player, world, worldConfig, 0);
    }

    private CompletableFuture<Boolean> dispatchTeleport(
        Player player,
        World world,
        RtpWorldConfig worldConfig,
        int attempt
    ) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        if (attempt >= 5) {
            return executeOnDemandRtp(player, world, worldConfig, 0);
        }

        RtpCandidate candidate = this.replenisher.popCandidate(world);

        if (candidate == null) {
            this.replenisher.recordDemand(world);
            return executeOnDemandRtp(player, world, worldConfig, 0);
        }

        long packedLoc = PackedLocation.fromCandidate(candidate);

        // Preload 3x3 inner ring chunk tickets
        this.ticketManager.addCandidateTickets(world, packedLoc);

        int blockX = (int) Math.floor(candidate.x());
        int blockZ = (int) Math.floor(candidate.z());

        return this.safetyInspector.inspectCandidate(world, worldConfig, blockX, blockZ, false).thenCompose(safeCandidate -> {
            if (player == null || !player.isOnline()) {
                this.ticketManager.removeCandidateTickets(world, packedLoc);
                return CompletableFuture.completedFuture(false);
            }

            if (safeCandidate == null) {
                this.ticketManager.removeCandidateTickets(world, packedLoc);
                return dispatchTeleport(player, world, worldConfig, attempt + 1);
            }

            Location dest = new Location(
                world,
                safeCandidate.x(),
                safeCandidate.y(),
                safeCandidate.z(),
                safeCandidate.yaw(),
                safeCandidate.pitch()
            );

            return player.teleportAsync(dest, TeleportCause.PLUGIN).thenApply(success -> {
                if (success) {
                    Bukkit.getRegionScheduler().runDelayed(
                        this.plugin,
                        dest,
                        t -> this.ticketManager.removeCandidateTickets(world, packedLoc),
                        100L
                    );

                    if (worldConfig.cooldownSeconds() > 0) {
                        this.cooldownMap.put(
                            player.getUniqueId(),
                            System.currentTimeMillis() + (worldConfig.cooldownSeconds() * 1000L)
                        );
                    }

                    sendMessage(
                        player,
                        this.configSupplier.get().messages().teleportSuccess(),
                        Placeholder.unparsed("x", String.valueOf(dest.getBlockX())),
                        Placeholder.unparsed("y", String.valueOf(dest.getBlockY())),
                        Placeholder.unparsed("z", String.valueOf(dest.getBlockZ())),
                        Placeholder.unparsed("world", world.getName())
                    );
                } else {
                    this.ticketManager.removeCandidateTickets(world, packedLoc);
                    sendMessage(player, this.configSupplier.get().messages().teleportFailed());
                }
                return success;
            });
        });
    }

    private CompletableFuture<Boolean> executeOnDemandRtp(
        Player player,
        World world,
        RtpWorldConfig worldConfig,
        int attempt
    ) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        if (attempt >= 10) {
            sendMessage(player, this.configSupplier.get().messages().searchFailed());
            return CompletableFuture.completedFuture(false);
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int candidateX;
        int candidateZ;

        if (worldConfig.shapeEnum() == RtpWorldConfig.Shape.SQUARE) {
            int span = Math.max(1, worldConfig.maxRadius());
            int inner = Math.max(0, Math.min(worldConfig.minRadius(), span - 1));
            int dx;
            int dz;
            int maxAttempts = 10;
            do {
                dx = rng.nextInt(-span, span + 1);
                dz = rng.nextInt(-span, span + 1);
            } while (Math.max(Math.abs(dx), Math.abs(dz)) < inner && --maxAttempts > 0);
            candidateX = worldConfig.centerX() + dx;
            candidateZ = worldConfig.centerZ() + dz;
        } else {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(rng.nextDouble() * (Math.pow(worldConfig.maxRadius(), 2) - Math.pow(worldConfig.minRadius(), 2)) + Math.pow(worldConfig.minRadius(), 2));
            candidateX = worldConfig.centerX() + (int) (r * Math.cos(angle));
            candidateZ = worldConfig.centerZ() + (int) (r * Math.sin(angle));
        }

        // For early attempts (0-3), prefer generated chunks to avoid chunk creation overhead.
        // For attempt 4+, allow chunk generation.
        boolean allowGeneration = attempt >= 3 || world.isChunkGenerated(candidateX >> 4, candidateZ >> 4);

        return this.safetyInspector.inspectCandidate(world, worldConfig, candidateX, candidateZ, allowGeneration).thenCompose(safeCandidate -> {
            if (player == null || !player.isOnline()) {
                return CompletableFuture.completedFuture(false);
            }

            if (safeCandidate == null) {
                return executeOnDemandRtp(player, world, worldConfig, attempt + 1);
            }

            long packedLoc = PackedLocation.fromCandidate(safeCandidate);
            this.ticketManager.addCandidateTickets(world, packedLoc);

            Location dest = new Location(
                world,
                safeCandidate.x(),
                safeCandidate.y(),
                safeCandidate.z(),
                safeCandidate.yaw(),
                safeCandidate.pitch()
            );

            return player.teleportAsync(dest, TeleportCause.PLUGIN).thenApply(success -> {
                if (success) {
                    Bukkit.getRegionScheduler().runDelayed(
                        this.plugin,
                        dest,
                        t -> this.ticketManager.removeCandidateTickets(world, packedLoc),
                        100L
                    );

                    if (worldConfig.cooldownSeconds() > 0) {
                        this.cooldownMap.put(
                            player.getUniqueId(),
                            System.currentTimeMillis() + (worldConfig.cooldownSeconds() * 1000L)
                        );
                    }

                    sendMessage(
                        player,
                        this.configSupplier.get().messages().teleportSuccess(),
                        Placeholder.unparsed("x", String.valueOf(dest.getBlockX())),
                        Placeholder.unparsed("y", String.valueOf(dest.getBlockY())),
                        Placeholder.unparsed("z", String.valueOf(dest.getBlockZ())),
                        Placeholder.unparsed("world", world.getName())
                    );
                } else {
                    this.ticketManager.removeCandidateTickets(world, packedLoc);
                    sendMessage(player, this.configSupplier.get().messages().teleportFailed());
                }
                return success;
            });
        });
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
    public void triggerReplenishment() {
        for (World world : this.plugin.getServer().getWorlds()) {
            this.replenisher.recordDemand(world);
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
