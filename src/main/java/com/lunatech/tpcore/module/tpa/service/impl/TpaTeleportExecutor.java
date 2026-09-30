package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Handles spatial safety validation, Folia cross-region scheduling, and asynchronous teleportation execution.
 */
final class TpaTeleportExecutor {

    private static final Vector ZERO_VECTOR = new Vector(0, 0, 0);

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaRepository repository;
    private final TpaEconomyService economyService;
    private final TpaMessenger messenger;
    private final TpaProtectionManager protectionManager;
    private final TpaEscrowManager escrowManager;

    TpaTeleportExecutor(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaRepository repository,
                        TpaEconomyService economyService, TpaMessenger messenger,
                        TpaProtectionManager protectionManager, TpaEscrowManager escrowManager) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repository = repository;
        this.economyService = economyService;
        this.messenger = messenger;
        this.protectionManager = protectionManager;
        this.escrowManager = escrowManager;
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    void performFinalTeleport(Player player, Player destinationPlayer, TpaType requestType, UUID senderId, double cost) {
        if (player == null || !player.isOnline() || player.isDead()) {
            this.escrowManager.refundSenderIfCharged(senderId, cost, "Teleporting player offline or dead");
            return;
        }
        if (destinationPlayer == null || !destinationPlayer.isOnline() || destinationPlayer.isDead()) {
            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
            this.escrowManager.refundSenderIfCharged(senderId, cost, "Destination player offline or dead");
            String dName = (destinationPlayer != null && destinationPlayer.getName() != null) ? destinationPlayer.getName() : "Player";
            this.messenger.sendMessage(player, config().messages().playerNotOnline(), "player", dName);
            return;
        }

        destinationPlayer.getScheduler().run(
            this.plugin,
            destTask -> {
                if (!player.isOnline() || !destinationPlayer.isOnline() || destinationPlayer.isDead()) {
                    this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                    this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                    this.escrowManager.refundSenderIfCharged(senderId, cost, "Player offline or dead before destination check");
                    return;
                }

                Location rawTargetLoc = destinationPlayer.getLocation();
                Location finalTargetLoc = rawTargetLoc;

                if (config().requireSafeLocation()) {
                    Location safeLoc = TpaSafetyInspector.findSafeLocation(rawTargetLoc);
                    if (safeLoc == null) {
                        this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                        this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                        if (config().refundOnUnsafeDestination()) {
                            this.escrowManager.refundSenderIfCharged(senderId, cost, "Unsafe destination");
                        }
                        player.getScheduler().run(
                            this.plugin,
                            pTask -> this.messenger.sendMessage(player, config().messages().unsafeDestination()),
                            null
                        );
                        this.messenger.sendMessage(destinationPlayer, config().messages().unsafeDestination());
                        return;
                    }
                    finalTargetLoc = safeLoc;
                }

                Location destination = finalTargetLoc;
                player.getScheduler().run(
                    this.plugin,
                    playerTask -> {
                        if (!player.isOnline() || player.isDead()) {
                            this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                            this.escrowManager.refundSenderIfCharged(senderId, cost, "Teleporting player offline or dead");
                            if (destinationPlayer.isOnline()) {
                                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(player.getUniqueId());
                                String pName = (op != null && op.getName() != null) ? op.getName() : "Player";
                                this.messenger.sendMessage(
                                    destinationPlayer,
                                    config().messages().playerNotOnline(),
                                    "player", pName
                                );
                            }
                            return;
                        }
                        if (!destinationPlayer.isOnline() || destination.getWorld() == null) {
                            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                            this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                            this.escrowManager.refundSenderIfCharged(senderId, cost, "Destination player offline or world unloaded");
                            if (player.isOnline()) {
                                this.messenger.sendMessage(player, config().messages().playerNotOnline(), "player", destinationPlayer.getName());
                            }
                            return;
                        }

                        String timing = config().getNormalizedChargeTiming();
                        if ("CHARGE_ON_SUCCESS".equals(timing) && cost > 0.0 && senderId != null) {
                            OfflinePlayer senderOp = TpaPlayerResolver.resolveOfflinePlayer(senderId);
                            final OfflinePlayer finalSenderOp = senderOp;
                            this.economyService.withdrawAsync(finalSenderOp, cost).whenComplete((charged, ex) -> {
                                if (ex != null) {
                                    this.plugin.getSLF4JLogger().warn("withdrawAsync failed exceptionally for sender {}", finalSenderOp.getUniqueId(), ex);
                                }
                                player.getScheduler().run(
                                    this.plugin,
                                    payTask -> {
                                        if (ex != null || !Boolean.TRUE.equals(charged)) {
                                            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                                            this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                                            TpaConfig cfg = config();
                                            boolean isSenderTeleporting = senderId.equals(player.getUniqueId());

                                            TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(cost));

                                            if (isSenderTeleporting) {
                                                if (player.isOnline()) {
                                                    this.messenger.sendMessage(player, cfg.messages().teleportCancelledInsufficientFunds(), costResolver);
                                                    if (cfg.enableSounds()) {
                                                        this.messenger.playSound(player, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                                                    }
                                                }
                                                if (destinationPlayer.isOnline() && !destinationPlayer.getUniqueId().equals(player.getUniqueId())) {
                                                    destinationPlayer.getScheduler().run(
                                                        this.plugin,
                                                        dTask -> {
                                                            this.messenger.sendMessage(destinationPlayer, cfg.messages().requestCancelledTarget(), "sender", player.getName());
                                                            if (cfg.enableSounds()) {
                                                                this.messenger.playSound(destinationPlayer, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                                                            }
                                                        },
                                                        null
                                                    );
                                                }
                                            } else {
                                                if (player.isOnline()) {
                                                    String dName = (destinationPlayer != null && destinationPlayer.getName() != null) ? destinationPlayer.getName() : "Player";
                                                    this.messenger.sendMessage(player, cfg.messages().requestCancelledTarget(), "sender", dName);
                                                    if (cfg.enableSounds()) {
                                                        this.messenger.playSound(player, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                                                    }
                                                }
                                                if (destinationPlayer.isOnline()) {
                                                    destinationPlayer.getScheduler().run(
                                                        this.plugin,
                                                        dTask -> {
                                                            this.messenger.sendMessage(destinationPlayer, cfg.messages().teleportCancelledInsufficientFunds(), costResolver);
                                                            if (cfg.enableSounds()) {
                                                                this.messenger.playSound(destinationPlayer, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                                                            }
                                                        },
                                                        null
                                                    );
                                                }
                                            }
                                            return;
                                        }
                                        executeTeleportFinalization(player, destinationPlayer, destination, requestType, senderId, cost);
                                    },
                                    () -> {
                                        this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                                        this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                                        if (Boolean.TRUE.equals(charged)) {
                                            this.escrowManager.refundSenderAfterCharge(senderId, cost, "Player retired during payment finalization");
                                        }
                                    }
                                );
                            });
                            return;
                        }

                        executeTeleportFinalization(player, destinationPlayer, destination, requestType, senderId, cost);
                    },
                    () -> {
                        this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                        this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                        this.escrowManager.refundSenderIfCharged(senderId, cost, "Teleporting player retired before teleport execution");
                    }
                );
            },
            () -> {
                this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                this.escrowManager.refundSenderIfCharged(senderId, cost, "Destination player retired before location safety check");
            }
        );
    }

    private void executeTeleportFinalization(Player player, Player destinationPlayer, Location destination, TpaType requestType, UUID senderId, double cost) {
        if (!player.isOnline() || player.isDead() || !destinationPlayer.isOnline()) {
            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
            this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
            this.escrowManager.refundSenderAfterCharge(senderId, cost, "Player offline or dead before teleport dispatch");
            return;
        }

        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.eject();
        player.setVelocity(ZERO_VECTOR);
        player.teleportAsync(destination).whenComplete((success, ex) -> {
            if (ex == null && Boolean.TRUE.equals(success)) {
                if (player.isOnline()) {
                    player.getScheduler().run(
                        this.plugin,
                        compTask -> {
                            player.setFallDistance(0.0f);
                            player.setFireTicks(0);
                            this.protectionManager.grantTeleportProtection(player);
                            TpaConfig cfg = config();
                            if (cfg.enableSounds()) {
                                this.messenger.playSound(player, cfg.completionSound(), (float) cfg.completionSoundVolume(), (float) cfg.completionSoundPitch());
                            }
                            Player rewardRecipient = (requestType == TpaType.TPA_TO) ? destinationPlayer : player;
                            OfflinePlayer payerPlayer = (requestType == TpaType.TPA_TO) ? player : destinationPlayer;
                            if (payerPlayer == null && senderId != null) {
                                payerPlayer = TpaPlayerResolver.resolveOfflinePlayer(senderId);
                            }
                            if ("CHARGE_ON_SUCCESS".equals(cfg.getNormalizedChargeTiming()) && senderId != null && cost > 0.0) {
                                Player senderPlayer = Bukkit.getPlayer(senderId);
                                if (senderPlayer != null && senderPlayer.isOnline()) {
                                    senderPlayer.getScheduler().run(this.plugin, t -> {
                                        if (senderPlayer.isOnline()) {
                                            TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(cost));
                                            this.messenger.sendMessage(senderPlayer, cfg.messages().moneyWithdrawn(), costResolver);
                                        }
                                    }, null);
                                }
                                this.economyService.processReward(rewardRecipient, payerPlayer, cost);
                            } else if (cost > 0.0) {
                                this.economyService.processReward(rewardRecipient, payerPlayer, cost);
                            }
                        },
                        () -> {
                            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                            this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                            this.escrowManager.refundSenderAfterCharge(senderId, cost, "Player retired before teleport finalization");
                        }
                    );
                } else {
                    this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                    this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                    this.escrowManager.refundSenderAfterCharge(senderId, cost, "Player offline after teleport completion");
                }
            } else {
                this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                String failReason = (ex != null) ? "Teleportation failed exceptionally" : "Teleportation cancelled or destination unsafe";
                this.escrowManager.refundSenderAfterCharge(senderId, cost, failReason);
                if (ex != null) {
                    this.plugin.getSLF4JLogger().warn("Player teleportAsync failed exceptionally for {}: {}", player.getName(), ex.getMessage());
                }
                if (player.isOnline()) {
                    player.getScheduler().run(
                        this.plugin,
                        pTask -> this.messenger.sendMessage(player, config().messages().unsafeDestination()),
                        null
                    );
                }
                if (destinationPlayer.isOnline()) {
                    destinationPlayer.getScheduler().run(
                        this.plugin,
                        dTask -> this.messenger.sendMessage(destinationPlayer, config().messages().unsafeDestination()),
                        null
                    );
                }
            }
        });
    }
}
