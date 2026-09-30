package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.util.MessageFormatter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Orchestrates teleport warmup countdowns, movement/damage tracking, and cancellation.
 */
final class TpaWarmupManager {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaRepository repository;
    private final TpaEconomyService economyService;
    private final TpaMessenger messenger;
    private final TpaEscrowManager escrowManager;
    private final TpaTeleportExecutor teleportExecutor;
    private final TpaWarmupRenderer renderer;
    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();

    TpaWarmupManager(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaRepository repository,
                     TpaEconomyService economyService, TpaMessenger messenger,
                     TpaEscrowManager escrowManager, TpaTeleportExecutor teleportExecutor) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repository = repository;
        this.economyService = economyService;
        this.messenger = messenger;
        this.escrowManager = escrowManager;
        this.teleportExecutor = teleportExecutor;
        this.renderer = new TpaWarmupRenderer(messenger);
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    boolean isPlayerInWarmup(UUID playerId) {
        if (playerId == null || this.activeWarmups.isEmpty()) {
            return false;
        }
        if (this.activeWarmups.containsKey(playerId)) {
            return true;
        }
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup != null && playerId.equals(warmup.destinationPlayerId())) {
                return true;
            }
        }
        return false;
    }

    boolean hasActiveWarmups() {
        return !this.activeWarmups.isEmpty();
    }

    void cancelWarmupsForDestination(UUID destinationId, String cancelMessage) {
        if (this.activeWarmups.isEmpty() || destinationId == null) {
            return;
        }
        List<UUID> toCancel = new ArrayList<>();
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup != null && destinationId.equals(warmup.destinationPlayerId())) {
                toCancel.add(warmup.teleportingPlayerId());
            }
        }
        for (UUID playerId : toCancel) {
            cancelWarmup(playerId, cancelMessage, true);
        }
    }

    void executeTeleportSequence(Player player, Player destinationPlayer, TpaType requestType, UUID senderId, double cost) {
        if (player == null || !player.isOnline() || player.isDead() || destinationPlayer == null || !destinationPlayer.isOnline() || destinationPlayer.isDead()) {
            this.repository.setCooldownEnd(senderId, 0L);
            this.escrowManager.refundSenderIfCharged(senderId, cost, "Teleport sequence aborted: player offline or dead");
            return;
        }

        if (isPlayerInWarmup(player.getUniqueId()) || isPlayerInWarmup(destinationPlayer.getUniqueId())) {
            this.repository.setCooldownEnd(senderId, 0L);
            this.escrowManager.refundSenderIfCharged(senderId, cost, "Teleport sequence aborted: player already in active warmup");
            return;
        }

        int warmupSeconds = config().warmupSeconds();
        if (warmupSeconds <= 0 || player.hasPermission(Permissions.TPA_BYPASS_WARMUP)) {
            this.teleportExecutor.performFinalTeleport(player, destinationPlayer, requestType, senderId, cost);
            return;
        }

        cancelWarmup(player.getUniqueId(), null, true);

        this.messenger.sendMessage(
            player,
            config().messages().warmupStart(),
            "seconds", String.valueOf(warmupSeconds)
        );

        TpaConfig cfg = config();
        BossBar bossBar = this.renderer.createBossBar(cfg, warmupSeconds);
        if (bossBar != null) {
            player.showBossBar(bossBar);
        }

        Location currentLoc = player.getLocation();
        BossBar finalBossBar = bossBar;

        ActiveWarmup warmup = new ActiveWarmup(
            player.getUniqueId(),
            destinationPlayer.getUniqueId(),
            destinationPlayer.getName(),
            currentLoc.getWorld().getName(),
            currentLoc.getX(),
            currentLoc.getY(),
            currentLoc.getZ(),
            warmupSeconds,
            finalBossBar,
            senderId,
            requestType,
            cost
        );
        this.activeWarmups.put(player.getUniqueId(), warmup);

        ScheduledTask task = player.getScheduler().runAtFixedRate(
            this.plugin,
            scheduledTask -> {
                if (!player.isOnline() || !destinationPlayer.isOnline() || warmup.isCancelled()) {
                    scheduledTask.cancel();
                    boolean destOffline = !destinationPlayer.isOnline();
                    boolean playerOffline = !player.isOnline();
                    String cancelMsg = (destOffline && player.isOnline()) ? config().messages().playerNotOnline() : null;
                    cancelWarmup(player.getUniqueId(), cancelMsg, destOffline || playerOffline);
                    return;
                }

                int rem = warmup.decrementRemainingSeconds();
                if (rem > 0) {
                    if (warmup.isCancelled()) {
                        scheduledTask.cancel();
                        return;
                    }
                    this.renderer.updateWarmupFeedback(player, config(), rem, warmupSeconds);
                    this.renderer.updateBossBar(finalBossBar, config(), rem, warmupSeconds);
                } else {
                    scheduledTask.cancel();
                    ActiveWarmup removed = this.activeWarmups.remove(player.getUniqueId());
                    if (removed != null && !removed.isCancelled()) {
                        removed.markCancelled();
                        if (finalBossBar != null) {
                            player.hideBossBar(finalBossBar);
                        }
                        if (config().enableTitle()) {
                            player.clearTitle();
                        }
                        this.teleportExecutor.performFinalTeleport(player, destinationPlayer, requestType, senderId, removed.cost());
                    }
                }
            },
            () -> cancelWarmup(player.getUniqueId(), null, true),
            20L,
            20L
        );

        warmup.setTask(task);
        if (warmup.isCancelled()) {
            if (task != null) {
                task.cancel();
            }
            cancelWarmup(player.getUniqueId(), null, true);
        }
    }

    void cancelWarmup(UUID playerId, String cancelMessageTemplate) {
        cancelWarmup(playerId, cancelMessageTemplate, false);
    }

    void cancelWarmup(UUID playerId, String cancelMessageTemplate, boolean forceRefund) {
        ActiveWarmup warmup = this.activeWarmups.remove(playerId);
        if (warmup != null) {
            warmup.markCancelled();
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
            if (warmup.senderId() != null) {
                this.repository.setCooldownEnd(warmup.senderId(), 0L);
            }
            if (warmup.teleportingPlayerId() != null) {
                this.repository.setCooldownEnd(warmup.teleportingPlayerId(), 0L);
            }
            if (warmup.destinationPlayerId() != null) {
                this.repository.setCooldownEnd(warmup.destinationPlayerId(), 0L);
            }
            String timing = config().getNormalizedChargeTiming();
            boolean isPayerCancelling = warmup.senderId() != null && warmup.senderId().equals(playerId);
            boolean shouldRefund = forceRefund || config().refundOnWarmupCancel() || !isPayerCancelling;
            if (("CHARGE_ON_SEND".equals(timing) || "CHARGE_ON_ACCEPT".equals(timing)) && shouldRefund && warmup.senderId() != null && warmup.cost() > 0.0) {
                this.escrowManager.refundSenderAfterCharge(warmup.senderId(), warmup.cost(), "Warmup cancelled");
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                final String dName = (warmup.destinationPlayerName() != null && !warmup.destinationPlayerName().isBlank())
                    ? warmup.destinationPlayerName()
                    : "Player";
                player.getScheduler().run(
                    this.plugin,
                    pTask -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (warmup.bossBar() != null) {
                            try {
                                player.hideBossBar(warmup.bossBar());
                            } catch (Throwable ignored) {
                            }
                        }
                        TpaConfig cfg = config();
                        if (cfg.enableSounds()) {
                            this.messenger.playSound(player, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                        }
                        if (cancelMessageTemplate != null && !cancelMessageTemplate.isBlank()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver playerResolver = Placeholder.unparsed("player", dName);
                            TagResolver targetResolver = Placeholder.unparsed("target", dName);
                            TagResolver combined = TagResolver.resolver(prefixResolver, playerResolver, targetResolver);

                            Component msgComp = this.messenger.miniMessage().deserialize(MessageFormatter.toMiniMessage(cancelMessageTemplate), combined);
                            player.sendMessage(msgComp);

                            if (cfg.enableActionBar()) {
                                player.sendActionBar(msgComp);
                            }

                            if (cfg.enableTitle()) {
                                String cancelTitleTpl = (cfg.cancelTitleFormat() != null && !cfg.cancelTitleFormat().isBlank())
                                    ? cfg.cancelTitleFormat()
                                    : "<red><bold>TPA CANCELLED</bold></red>";
                                Component titleComp = this.messenger.miniMessage().deserialize(MessageFormatter.toMiniMessage(cancelTitleTpl), combined);
                                Title title = Title.title(
                                    titleComp,
                                    msgComp,
                                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))
                                );
                                player.showTitle(title);
                            }
                        } else if (cfg.enableTitle()) {
                            player.clearTitle();
                        }
                    },
                    null
                );
            }
            if (warmup.destinationPlayerId() != null) {
                Player dest = Bukkit.getPlayer(warmup.destinationPlayerId());
                if (dest != null && dest.isOnline()) {
                    String pName = (player != null && player.getName() != null) ? player.getName() : null;
                    if (pName == null) {
                        OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(playerId);
                        pName = (op != null && op.getName() != null) ? op.getName() : "Player";
                    }
                    final String teleporterName = pName;
                    dest.getScheduler().run(
                        this.plugin,
                        dTask -> this.messenger.sendMessage(
                            dest,
                            config().messages().requestCancelledTarget(),
                            "sender", teleporterName
                        ),
                        null
                    );
                }
            }
        }
    }

    void handlePlayerDamage(UUID playerId) {
        if (config().cancelOnDamage() && this.activeWarmups.containsKey(playerId)) {
            cancelWarmup(playerId, config().messages().warmupCancelledDamage());
        }
    }

    void handlePlayerMove(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        handlePlayerMove(player, player.getLocation());
    }

    void handlePlayerMove(Player player, Location to) {
        if (player == null || to == null) {
            return;
        }
        if (!config().cancelOnMove()) {
            return;
        }
        ActiveWarmup warmup = this.activeWarmups.get(player.getUniqueId());
        if (warmup != null && warmup.hasMoved(to)) {
            cancelWarmup(player.getUniqueId(), config().messages().warmupCancelledMove());
        }
    }

    void handlePlayerTeleport(UUID playerId) {
        if (playerId != null && this.activeWarmups.containsKey(playerId)) {
            cancelWarmup(playerId, null);
        }
    }

    void handlePlayerDeath(UUID playerId) {
        if (playerId != null && this.activeWarmups.containsKey(playerId)) {
            cancelWarmup(playerId, null);
        }
    }

    Map<UUID, ActiveWarmup> activeWarmups() {
        return this.activeWarmups;
    }

    void clear() {
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
            Player player = Bukkit.getPlayer(warmup.teleportingPlayerId());
            if (player != null && player.isOnline()) {
                if (warmup.bossBar() != null) {
                    player.hideBossBar(warmup.bossBar());
                }
                if (config().enableTitle()) {
                    player.clearTitle();
                }
            }
        }
        this.activeWarmups.clear();
    }
}
