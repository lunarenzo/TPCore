package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Handles acceptance processing and validation for incoming teleport requests.
 */
final class TpaRequestAcceptor {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaRepository repository;
    private final TpaEconomyService economyService;
    private final TpaMessenger messenger;
    private final TpaEscrowManager escrowManager;
    private final TpaWarmupManager warmupManager;

    TpaRequestAcceptor(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaRepository repository,
                       TpaEconomyService economyService, TpaMessenger messenger,
                       TpaEscrowManager escrowManager, TpaWarmupManager warmupManager) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repository = repository;
        this.economyService = economyService;
        this.messenger = messenger;
        this.escrowManager = escrowManager;
        this.warmupManager = warmupManager;
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    void acceptRequest(Player target, String optionalSenderName) {
        acceptRequestInternal(target, optionalSenderName, true);
    }

    void acceptRequestInternal(Player target, String optionalSenderName, boolean notifyMessages) {
        if (target != null && target.isOnline()) {
            this.escrowManager.claimPendingRefunds(target);
        }
        Collection<TpaRequest> rawIncoming = this.repository.getIncomingRequests(target.getUniqueId());

        if (rawIncoming.isEmpty()) {
            if (notifyMessages) {
                this.messenger.sendMessage(target, config().messages().noPendingRequests());
            }
            return;
        }

        List<TpaRequest> incoming = new ArrayList<>();
        int timeoutSeconds = config().requestTimeoutSeconds();
        for (TpaRequest req : rawIncoming) {
            if (req != null && !req.isExpired(timeoutSeconds)) {
                incoming.add(req);
            } else if (req != null) {
                pruneExpiredRequest(req);
            }
        }

        if (incoming.isEmpty()) {
            if (notifyMessages) {
                this.messenger.sendMessage(target, config().messages().noPendingRequests());
            }
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            targetRequest = resolveMatchingRequest(incoming, optionalSenderName);
        } else if (incoming.size() == 1 || !notifyMessages) {
            targetRequest = incoming.get(0);
        } else {
            if (notifyMessages) {
                this.messenger.sendMessage(target, config().messages().multiplePendingRequests());
            }
            return;
        }

        if (targetRequest == null || targetRequest.isExpired(config().requestTimeoutSeconds())) {
            if (notifyMessages) {
                this.messenger.sendMessage(target, config().messages().noPendingRequests());
            }
            if (targetRequest != null) {
                pruneExpiredRequest(targetRequest);
            }
            return;
        }

        if (this.warmupManager.isPlayerInWarmup(target.getUniqueId()) || this.warmupManager.isPlayerInWarmup(targetRequest.senderId())) {
            return;
        }

        if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
            if (notifyMessages) {
                this.messenger.sendMessage(target, config().messages().noPendingRequests());
            }
            return;
        }

        Player sender = Bukkit.getPlayer(targetRequest.senderId());
        if (sender != null && sender.isOnline()) {
            this.escrowManager.claimPendingRefunds(sender);
        }
        String timing = config().getNormalizedChargeTiming();
        if ("CHARGE_ON_SEND".equals(timing)) {
            if (sender == null || !sender.isOnline()) {
                if (targetRequest.cost() > 0.0) {
                    OfflinePlayer senderOp = TpaPlayerResolver.resolveOfflinePlayer(targetRequest.senderId());
                    this.economyService.processRefund(senderOp, targetRequest.cost(), "Sender offline on accept");
                }
            }
        }

        if (sender == null || !sender.isOnline()) {
            this.repository.setCooldownEnd(targetRequest.senderId(), 0L);
            if (notifyMessages) {
                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(targetRequest.senderId());
                String senderName = (op != null && op.getName() != null) ? op.getName() : "Player";
                this.messenger.sendMessage(
                    target,
                    config().messages().playerNotOnline(),
                    "player", senderName
                );
            }
            return;
        }

        List<String> disabledWorlds = config().disabledWorlds();
        if (disabledWorlds != null && !disabledWorlds.isEmpty()) {
            Player teleportingPlayer = (targetRequest.type() == TpaType.TPA_TO) ? sender : target;
            Player destinationPlayer = (targetRequest.type() == TpaType.TPA_TO) ? target : sender;
            if (!teleportingPlayer.hasPermission(Permissions.TPA_BYPASS_WORLD)) {
                String dWorld = destinationPlayer.getWorld().getName();
                String tWorld = teleportingPlayer.getWorld().getName();
                if (disabledWorlds.stream().anyMatch(dWorld::equalsIgnoreCase) || disabledWorlds.stream().anyMatch(tWorld::equalsIgnoreCase)) {
                    String disabledW = disabledWorlds.stream().anyMatch(dWorld::equalsIgnoreCase) ? dWorld : tWorld;
                    this.repository.setCooldownEnd(targetRequest.senderId(), 0L);
                    this.escrowManager.refundSenderIfCharged(targetRequest.senderId(), targetRequest.cost(), "Teleport in disabled world");
                    if (notifyMessages) {
                        this.messenger.sendMessage(target, config().messages().worldDisabled(), "world", disabledW);
                    }
                    this.messenger.sendMessage(sender, config().messages().worldDisabled(), "world", disabledW);
                    return;
                }
            }
        }

        if ("CHARGE_ON_ACCEPT".equals(timing) && targetRequest.cost() > 0.0) {
            final UUID reqSenderId = targetRequest.senderId();
            final UUID targetId = target.getUniqueId();
            final double cost = targetRequest.cost();
            final TpaRequest finalTargetReq = targetRequest;
            final boolean finalNotify = notifyMessages;

            this.economyService.processSendCostAsync(sender, cost).whenComplete((charged, ex) -> {
                if (ex != null) {
                    this.plugin.getSLF4JLogger().warn("processSendCostAsync failed exceptionally for sender {}", reqSenderId, ex);
                }
                target.getScheduler().run(
                    this.plugin,
                    tTask -> {
                        if (ex != null || !Boolean.TRUE.equals(charged)) {
                            this.repository.setCooldownEnd(reqSenderId, 0L);
                            Player currentSender = Bukkit.getPlayer(reqSenderId);
                            if (currentSender != null && currentSender.isOnline()) {
                                currentSender.getScheduler().run(this.plugin, sTask -> TpaMenuCloser.closeConfirmationMenuIfOpen(currentSender, targetId), null);
                            }
                            TpaMenuCloser.closeConfirmationMenuIfOpen(target, reqSenderId);
                            if (finalNotify && target.isOnline()) {
                                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(reqSenderId);
                                String senderName = (op != null && op.getName() != null) ? op.getName() : "Player";
                                this.messenger.sendMessage(
                                    target,
                                    config().messages().targetAcceptFailedInsufficientFunds(),
                                    "sender", senderName
                                );
                            }
                            return;
                        }
                        if (!target.isOnline() || target.isDead()) {
                            this.repository.setCooldownEnd(reqSenderId, 0L);
                            this.escrowManager.refundSenderAfterCharge(reqSenderId, cost, "Target disconnected during payment processing");
                            return;
                        }
                        Player currentSender = Bukkit.getPlayer(reqSenderId);
                        if (currentSender == null || !currentSender.isOnline() || currentSender.isDead()) {
                            this.repository.setCooldownEnd(reqSenderId, 0L);
                            this.escrowManager.refundSenderAfterCharge(reqSenderId, cost, "Sender disconnected during payment processing");
                            if (finalNotify && target.isOnline()) {
                                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(reqSenderId);
                                String senderName = (op != null && op.getName() != null) ? op.getName() : "Player";
                                this.messenger.sendMessage(
                                    target,
                                    config().messages().playerNotOnline(),
                                    "player", senderName
                                );
                            }
                            return;
                        }
                        finalizeAcceptAndDispatch(target, currentSender, finalTargetReq, finalNotify, timing);
                    },
                    () -> {
                        this.repository.setCooldownEnd(reqSenderId, 0L);
                        if (Boolean.TRUE.equals(charged)) {
                            this.escrowManager.refundSenderAfterCharge(reqSenderId, cost, "Target retired during payment processing");
                        }
                    }
                );
            });
            return;
        } else if ("CHARGE_ON_SUCCESS".equals(timing)) {
            double cost = targetRequest.cost();
            if (cost > 0.0 && !this.economyService.has(sender, cost)) {
                final UUID reqSenderId = targetRequest.senderId();
                final UUID targetId = target.getUniqueId();
                this.repository.setCooldownEnd(reqSenderId, 0L);
                final boolean notify = notifyMessages;
                sender.getScheduler().run(this.plugin, sTask -> {
                    TpaMenuCloser.closeConfirmationMenuIfOpen(sender, targetId);
                    if (notify && sender.isOnline()) {
                        double balance = this.economyService.getBalance(sender);
                        TpaConfig cfg = config();
                        TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(cost));
                        TagResolver balResolver = Placeholder.unparsed("balance", this.economyService.format(balance));
                        this.messenger.sendMessage(sender, cfg.messages().insufficientFunds(), costResolver, balResolver);
                    }
                }, null);
                target.getScheduler().run(this.plugin, tTask -> TpaMenuCloser.closeConfirmationMenuIfOpen(target, reqSenderId), null);
                if (notifyMessages) {
                    this.messenger.sendMessage(
                        target,
                        config().messages().targetAcceptFailedInsufficientFunds(),
                        "sender", sender.getName()
                    );
                }
                return;
            }
        }

        finalizeAcceptAndDispatch(target, sender, targetRequest, notifyMessages, timing);
    }

    private void finalizeAcceptAndDispatch(Player target, Player sender, TpaRequest targetRequest, boolean notifyMessages, String timing) {
        if (notifyMessages) {
            this.messenger.sendMessage(
                target,
                config().messages().requestAcceptedTarget(),
                "sender", sender.getName()
            );

            sender.getScheduler().run(
                this.plugin,
                sTask -> {
                    TpaMenuCloser.closeConfirmationMenuIfOpen(sender, target.getUniqueId());
                    this.messenger.sendMessage(
                        sender,
                        config().messages().requestAcceptedSender(),
                        "target", target.getName()
                    );
                },
                null
            );
        } else {
            sender.getScheduler().run(
                this.plugin,
                sTask -> TpaMenuCloser.closeConfirmationMenuIfOpen(sender, target.getUniqueId()),
                null
            );
        }

        final UUID reqSenderId = targetRequest.senderId();
        target.getScheduler().run(
            this.plugin,
            tTask -> TpaMenuCloser.closeConfirmationMenuIfOpen(target, reqSenderId),
            null
        );

        Collection<TpaRequest> otherOutgoing = this.repository.getOutgoingRequests(reqSenderId);
        if (otherOutgoing != null && !otherOutgoing.isEmpty()) {
            for (TpaRequest otherReq : new ArrayList<>(otherOutgoing)) {
                if (otherReq != null && !otherReq.targetId().equals(targetRequest.targetId())) {
                    if (this.repository.removeRequest(otherReq.targetId(), otherReq.senderId())) {
                        if ("CHARGE_ON_SEND".equals(timing) && otherReq.cost() > 0.0) {
                            OfflinePlayer senderOp = TpaPlayerResolver.resolveOfflinePlayer(otherReq.senderId());
                            this.economyService.processRefund(senderOp, otherReq.cost(), "Auto-cancelled other outgoing request on accept");
                        }
                        Player otherTarget = Bukkit.getPlayer(otherReq.targetId());
                        if (otherTarget != null && otherTarget.isOnline()) {
                            UUID otherSenderId = otherReq.senderId();
                            otherTarget.getScheduler().run(
                                this.plugin,
                                otTask -> TpaMenuCloser.closeConfirmationMenuIfOpen(otherTarget, otherSenderId),
                                null
                            );
                        }
                    }
                }
            }
        }

        final TpaType reqType = targetRequest.type();
        final double reqCost = targetRequest.cost();
        Player teleportingPlayer = (reqType == TpaType.TPA_TO) ? sender : target;
        Player destinationPlayer = (reqType == TpaType.TPA_TO) ? target : sender;

        teleportingPlayer.getScheduler().run(
            this.plugin,
            tTask -> this.warmupManager.executeTeleportSequence(teleportingPlayer, destinationPlayer, reqType, reqSenderId, reqCost),
            () -> {
                this.repository.setCooldownEnd(reqSenderId, 0L);
                this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                this.escrowManager.refundSenderIfCharged(reqSenderId, reqCost, "Teleport sequence aborted: player retired/disconnected");
            }
        );
    }

    private void pruneExpiredRequest(TpaRequest req) {
        if (req != null && this.repository.removeRequest(req.targetId(), req.senderId())) {
            this.repository.setCooldownEnd(req.senderId(), 0L);
            if ("CHARGE_ON_SEND".equals(config().getNormalizedChargeTiming()) && config().refundOnExpire() && req.cost() > 0.0) {
                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayer(req.senderId());
                this.economyService.processRefund(op, req.cost(), "Request expired");
            }
        }
    }

    private TpaRequest resolveMatchingRequest(List<TpaRequest> requests, String senderName) {
        for (TpaRequest req : requests) {
            if (req.senderId().toString().equalsIgnoreCase(senderName)) {
                return req;
            }
            Player p = Bukkit.getPlayer(req.senderId());
            if (p != null && p.getName().equalsIgnoreCase(senderName)) {
                return req;
            }
            OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(req.senderId());
            if (op != null && op.getName() != null && op.getName().equalsIgnoreCase(senderName)) {
                return req;
            }
        }
        return null;
    }
}
