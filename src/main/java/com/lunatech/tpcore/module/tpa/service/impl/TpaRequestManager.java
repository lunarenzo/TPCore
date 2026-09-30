package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Manages request denial, cancellation, lookups, and expiration sweeping.
 */
final class TpaRequestManager {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaRepository repository;
    private final TpaEconomyService economyService;
    private final TpaMessenger messenger;
    private final TpaRequestAcceptor requestAcceptor;
    private final ScheduledTask sweeperTask;

    TpaRequestManager(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaRepository repository,
                      TpaEconomyService economyService, TpaMessenger messenger,
                      TpaEscrowManager escrowManager, TpaWarmupManager warmupManager) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repository = repository;
        this.economyService = economyService;
        this.messenger = messenger;
        this.requestAcceptor = new TpaRequestAcceptor(plugin, configSupplier, repository, economyService, messenger, escrowManager, warmupManager);
        this.sweeperTask = startExpirationSweeper();
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    void acceptRequest(Player target, String optionalSenderName) {
        this.requestAcceptor.acceptRequest(target, optionalSenderName);
    }

    void acceptRequestInternal(Player target, String optionalSenderName, boolean notifyMessages) {
        this.requestAcceptor.acceptRequestInternal(target, optionalSenderName, notifyMessages);
    }

    void denyRequest(Player target, String optionalSenderName) {
        List<TpaRequest> incoming = filterValidRequests(this.repository.getIncomingRequests(target.getUniqueId()));
        if (incoming.isEmpty()) {
            this.messenger.sendMessage(target, config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            targetRequest = resolveMatchingRequest(incoming, optionalSenderName);
        } else if (incoming.size() == 1) {
            targetRequest = incoming.get(0);
        } else {
            this.messenger.sendMessage(target, config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest != null) {
            if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
                this.messenger.sendMessage(target, config().messages().noPendingRequests());
                return;
            }
            this.repository.setCooldownEnd(targetRequest.senderId(), 0L);
            if ("CHARGE_ON_SEND".equals(config().getNormalizedChargeTiming()) && config().refundOnDeny() && targetRequest.cost() > 0.0) {
                OfflinePlayer senderOp = TpaPlayerResolver.resolveOfflinePlayer(targetRequest.senderId());
                this.economyService.processRefund(senderOp, targetRequest.cost(), "Denied by target");
            }
            Player sender = Bukkit.getPlayer(targetRequest.senderId());
            if (sender != null && sender.isOnline()) {
                UUID reqTargetId = targetRequest.targetId();
                final String targetDisplayName = target.getName();
                sender.getScheduler().run(
                    this.plugin,
                    t -> {
                        TpaMenuCloser.closeConfirmationMenuIfOpen(sender, reqTargetId);
                        this.messenger.sendMessage(
                            sender,
                            config().messages().requestDeniedSender(),
                            "target", targetDisplayName
                        );
                        if (config().enableSounds()) {
                            TpaConfig cfg = config();
                            this.messenger.playSound(sender, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                        }
                    },
                    null
                );
            }
            TpaMenuCloser.closeConfirmationMenuIfOpen(target, targetRequest.senderId());
            String senderDisplayName = (sender != null) ? sender.getName() : null;
            if (senderDisplayName == null) {
                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(targetRequest.senderId());
                senderDisplayName = (op != null && op.getName() != null) ? op.getName() : "Player";
            }
            this.messenger.sendMessage(
                target,
                config().messages().requestDeniedTarget(),
                "sender", (senderDisplayName != null) ? senderDisplayName : "Player"
            );
            if (config().enableSounds()) {
                TpaConfig cfg = config();
                this.messenger.playSound(target, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
            }
        } else {
            this.messenger.sendMessage(target, config().messages().noPendingRequests());
        }
    }

    void cancelRequest(Player sender, String optionalTargetName) {
        List<TpaRequest> outgoing = filterValidRequests(this.repository.getOutgoingRequests(sender.getUniqueId()));
        if (outgoing.isEmpty()) {
            this.messenger.sendMessage(sender, config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalTargetName != null && !optionalTargetName.isBlank()) {
            targetRequest = resolveMatchingOutgoingRequest(outgoing, optionalTargetName);
        } else if (outgoing.size() == 1) {
            targetRequest = outgoing.get(0);
        } else {
            this.messenger.sendMessage(sender, config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest != null) {
            if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
                this.messenger.sendMessage(sender, config().messages().noPendingRequests());
                return;
            }
            this.repository.setCooldownEnd(targetRequest.senderId(), 0L);
            if ("CHARGE_ON_SEND".equals(config().getNormalizedChargeTiming()) && config().refundOnCancelBySender() && targetRequest.cost() > 0.0) {
                this.economyService.processRefund(sender, targetRequest.cost(), "Cancelled by sender");
            }
            Player target = Bukkit.getPlayer(targetRequest.targetId());
            if (target != null && target.isOnline()) {
                UUID reqSenderId = targetRequest.senderId();
                final String senderDisplayName = sender.getName();
                target.getScheduler().run(
                    this.plugin,
                    t -> {
                        TpaMenuCloser.closeConfirmationMenuIfOpen(target, reqSenderId);
                        this.messenger.sendMessage(
                            target,
                            config().messages().requestCancelledTarget(),
                            "sender", senderDisplayName
                        );
                        if (config().enableSounds()) {
                            TpaConfig cfg = config();
                            this.messenger.playSound(target, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                        }
                    },
                    null
                );
            }
            TpaMenuCloser.closeConfirmationMenuIfOpen(sender, targetRequest.targetId());
            String targetDisplayName = (target != null) ? target.getName() : null;
            if (targetDisplayName == null) {
                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(targetRequest.targetId());
                targetDisplayName = (op != null && op.getName() != null) ? op.getName() : "Player";
            }
            this.messenger.sendMessage(
                sender,
                config().messages().requestCancelledSender(),
                "target", (targetDisplayName != null) ? targetDisplayName : "Player"
            );
            if (config().enableSounds()) {
                TpaConfig cfg = config();
                this.messenger.playSound(sender, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
            }
        } else {
            this.messenger.sendMessage(sender, config().messages().noPendingRequests());
        }
    }

    private ScheduledTask startExpirationSweeper() {
        return this.plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            this.plugin,
            task -> {
                List<TpaRequest> expired = new ArrayList<>();
                this.repository.forEachRequest(request -> {
                    if (request.isExpired(this.config().requestTimeoutSeconds())) {
                        expired.add(request);
                    }
                });

                this.repository.clearExpiredCooldowns();

                if (!expired.isEmpty()) {
                    for (TpaRequest request : expired) {
                        if (!this.repository.removeRequest(request.targetId(), request.senderId())) {
                            continue;
                        }

                        this.repository.setCooldownEnd(request.senderId(), 0L);

                        if ("CHARGE_ON_SEND".equals(this.config().getNormalizedChargeTiming()) && this.config().refundOnExpire() && request.cost() > 0.0) {
                            OfflinePlayer senderOp = Bukkit.getPlayer(request.senderId());
                            if (senderOp == null) {
                                senderOp = TpaPlayerResolver.resolveOfflinePlayerIfCached(request.senderId());
                            }
                            if (senderOp == null) {
                                senderOp = Bukkit.getOfflinePlayer(request.senderId());
                            }
                            this.economyService.processRefund(senderOp, request.cost(), "Request expired");
                        }

                        Player sender = Bukkit.getPlayer(request.senderId());
                        if (sender != null && sender.isOnline()) {
                            Player target = Bukkit.getPlayer(request.targetId());
                            String targetName = (target != null && target.isOnline()) ? target.getName() : null;
                            if (targetName == null) {
                                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(request.targetId());
                                targetName = (op != null) ? op.getName() : null;
                            }
                            String finalTargetName = (targetName != null) ? targetName : "Player";
                            sender.getScheduler().run(
                                this.plugin,
                                t -> {
                                    TpaMenuCloser.closeConfirmationMenuIfOpen(sender, request.targetId());
                                    this.messenger.sendMessage(
                                        sender,
                                        this.config().messages().requestExpired(),
                                        "player", finalTargetName
                                    );
                                    if (this.config().enableSounds()) {
                                        TpaConfig cfg = this.config();
                                        this.messenger.playSound(sender, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                                    }
                                },
                                null
                            );
                        }

                        Player target = Bukkit.getPlayer(request.targetId());
                        if (target != null && target.isOnline()) {
                            Player senderPlayer = Bukkit.getPlayer(request.senderId());
                            String senderName = (senderPlayer != null && senderPlayer.isOnline()) ? senderPlayer.getName() : null;
                            if (senderName == null) {
                                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(request.senderId());
                                senderName = (op != null) ? op.getName() : null;
                            }
                            String finalSenderName = (senderName != null) ? senderName : "Player";
                            target.getScheduler().run(
                                this.plugin,
                                t -> {
                                    TpaMenuCloser.closeConfirmationMenuIfOpen(target, request.senderId());
                                    this.messenger.sendMessage(
                                        target,
                                        this.config().messages().requestExpired(),
                                        "player", finalSenderName
                                    );
                                    if (this.config().enableSounds()) {
                                        TpaConfig cfg = this.config();
                                        this.messenger.playSound(target, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                                    }
                                },
                                null
                            );
                        }
                    }
                }
            },
            20L,
            20L
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

    private TpaRequest resolveMatchingOutgoingRequest(List<TpaRequest> requests, String targetName) {
        for (TpaRequest req : requests) {
            Player p = Bukkit.getPlayer(req.targetId());
            if (p != null && p.getName().equalsIgnoreCase(targetName)) {
                return req;
            }
            OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(req.targetId());
            if (op != null && op.getName() != null && op.getName().equalsIgnoreCase(targetName)) {
                return req;
            }
        }
        return null;
    }

    private List<TpaRequest> filterValidRequests(Collection<TpaRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<TpaRequest> valid = new ArrayList<>(raw.size());
        int timeoutSeconds = config().requestTimeoutSeconds();
        for (TpaRequest req : raw) {
            if (req != null && !req.isExpired(timeoutSeconds)) {
                valid.add(req);
            } else if (req != null) {
                pruneExpiredRequest(req);
            }
        }
        return valid;
    }

    TpaRequest findPendingRequest(Player target, String optionalSenderName) {
        if (target == null) {
            return null;
        }
        List<TpaRequest> incoming = filterValidRequests(this.repository.getIncomingRequests(target.getUniqueId()));
        if (incoming.isEmpty()) {
            return null;
        }
        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            return resolveMatchingRequest(incoming, optionalSenderName);
        }
        return (incoming.size() == 1) ? incoming.get(0) : null;
    }

    Collection<TpaRequest> getPendingRequestsForTarget(Player target) {
        if (target == null) {
            return Collections.emptyList();
        }
        return filterValidRequests(this.repository.getIncomingRequests(target.getUniqueId()));
    }

    Collection<TpaRequest> getOutgoingRequestsForSender(Player sender) {
        if (sender == null) {
            return Collections.emptyList();
        }
        return filterValidRequests(this.repository.getOutgoingRequests(sender.getUniqueId()));
    }

    void shutdown() {
        if (this.sweeperTask != null) {
            this.sweeperTask.cancel();
        }
    }
}
