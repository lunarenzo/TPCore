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

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Handles sending and validation of single and bulk TPA requests.
 */
final class TpaSendRequestHandler {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaRepository repository;
    private final TpaEconomyService economyService;
    private final TpaMessenger messenger;
    private final TpaEscrowManager escrowManager;
    private final TpaWarmupManager warmupManager;
    private final BiConsumer<Player, String> autoAcceptDispatcher;

    TpaSendRequestHandler(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaRepository repository,
                          TpaEconomyService economyService, TpaMessenger messenger,
                          TpaEscrowManager escrowManager, TpaWarmupManager warmupManager,
                          BiConsumer<Player, String> autoAcceptDispatcher) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repository = repository;
        this.economyService = economyService;
        this.messenger = messenger;
        this.escrowManager = escrowManager;
        this.warmupManager = warmupManager;
        this.autoAcceptDispatcher = autoAcceptDispatcher;
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    void sendRequest(Player sender, Player target, TpaType type) {
        processSingleSendRequest(sender, target, type, true, false);
    }

    void sendRequest(Player sender, UUID targetId, TpaType type) {
        if (sender == null || targetId == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target != null && target.isOnline()) {
            sendRequest(sender, target, type);
        } else if (sender.isOnline()) {
            OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(targetId);
            String targetName = (op != null && op.getName() != null) ? op.getName() : "Player";
            this.messenger.sendMessage(sender, config().messages().playerNotOnline(), "player", targetName);
        }
    }

    void sendBulkRequests(Player sender, List<Player> targets, TpaType type) {
        if (sender == null || targets == null || targets.isEmpty()) {
            return;
        }

        int cooldownSeconds = config().requestCooldownSeconds();
        if (cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            long cooldownEnd = this.repository.getCooldownEnd(sender.getUniqueId());
            long now = System.currentTimeMillis();
            if (cooldownEnd > now) {
                long remSeconds = (cooldownEnd - now + 999L) / 1000L;
                this.messenger.sendMessage(
                    sender,
                    config().messages().cooldownActive(),
                    "seconds", String.valueOf(remSeconds)
                );
                return;
            }
        }

        boolean isBulk = targets.size() > 1;
        double cost = this.economyService.getCost(sender, type);
        String timing = config().getNormalizedChargeTiming();
        if (isBulk && "CHARGE_ON_SEND".equals(timing) && cost > 0.0) {
            int maxReqPerPlayer = config().maxPendingRequestsPerPlayer();
            int timeoutSecs = config().requestTimeoutSeconds();
            long eligibleCount = targets.stream()
                .filter(t -> {
                    if (t == null || !t.isOnline()) return false;
                    if (!config().allowSelfTpa() && sender.getUniqueId().equals(t.getUniqueId())) return false;
                    if (this.warmupManager.isPlayerInWarmup(t.getUniqueId())) return false;
                    if (this.repository.isTpaToggledOff(t.getUniqueId())) return false;
                    UUID tId = t.getUniqueId();
                    if (this.repository.isPlayerBlocked(tId, sender.getUniqueId())) return false;
                    if (this.repository.isPlayerBlocked(sender.getUniqueId(), tId)) return false;
                    if (this.repository.getRequest(tId, sender.getUniqueId()).filter(r -> !r.isExpired(timeoutSecs)).isPresent()) return false;
                    if (maxReqPerPlayer > 0) {
                        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(tId);
                        int active = 0;
                        for (TpaRequest r : incoming) {
                            if (!r.isExpired(timeoutSecs)) active++;
                        }
                        if (active >= maxReqPerPlayer) return false;
                    }
                    return true;
                })
                .count();
            double totalCost = eligibleCount * cost;
            if (eligibleCount > 0 && !this.economyService.has(sender, totalCost)) {
                double balance = this.economyService.getBalance(sender);
                TpaConfig cfg = config();
                TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(totalCost));
                TagResolver balResolver = Placeholder.unparsed("balance", this.economyService.format(balance));
                this.messenger.sendMessage(sender, cfg.messages().insufficientFunds(), costResolver, balResolver);
                return;
            }
        }

        boolean sentAny = false;
        for (Player target : targets) {
            if (target != null && target.isOnline()) {
                if (this.warmupManager.isPlayerInWarmup(sender.getUniqueId())) {
                    break;
                }
                if (this.warmupManager.isPlayerInWarmup(target.getUniqueId())) {
                    continue;
                }
                boolean isAutoAccept = this.repository.isAutoAcceptEnabled(target.getUniqueId());
                if (processSingleSendRequest(sender, target, type, false, isBulk)) {
                    sentAny = true;
                    if (isAutoAccept || this.warmupManager.isPlayerInWarmup(sender.getUniqueId())) {
                        break;
                    }
                }
            }
        }

        if (sentAny && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            this.repository.setCooldownEnd(sender.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        } else if (!sentAny && isBulk) {
            if (cost > 0.0 && !this.economyService.has(sender, cost)) {
                double balance = this.economyService.getBalance(sender);
                TpaConfig cfg = config();
                TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(cost));
                TagResolver balResolver = Placeholder.unparsed("balance", this.economyService.format(balance));
                this.messenger.sendMessage(sender, cfg.messages().insufficientFunds(), costResolver, balResolver);
            }
        }
    }

    boolean processSingleSendRequest(Player sender, Player target, TpaType type, boolean applyCooldown, boolean isBulk) {
        if (sender == null || target == null || !target.isOnline()) {
            if (!isBulk && sender != null && sender.isOnline()) {
                String targetName = (target != null && target.getName() != null) ? target.getName() : "Player";
                this.messenger.sendMessage(sender, config().messages().playerNotOnline(), "player", targetName);
            }
            return false;
        }

        if (!config().allowSelfTpa() && sender.getUniqueId().equals(target.getUniqueId())) {
            if (!isBulk) {
                this.messenger.sendMessage(sender, config().messages().rejectSelfTpa());
            }
            return false;
        }

        if (this.repository.isTpaToggledOff(target.getUniqueId())
            || this.repository.isPlayerBlocked(target.getUniqueId(), sender.getUniqueId())
            || this.repository.isPlayerBlocked(sender.getUniqueId(), target.getUniqueId())) {
            if (!isBulk) {
                this.messenger.sendMessage(
                    sender,
                    config().messages().targetToggledOff(),
                    "target", target.getName()
                );
            }
            return false;
        }

        if (this.warmupManager.isPlayerInWarmup(sender.getUniqueId())) {
            return false;
        }
        if (this.warmupManager.isPlayerInWarmup(target.getUniqueId())) {
            return false;
        }

        int cooldownSeconds = config().requestCooldownSeconds();
        if (applyCooldown && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            long cooldownEnd = this.repository.getCooldownEnd(sender.getUniqueId());
            long now = System.currentTimeMillis();
            if (cooldownEnd > now) {
                if (!isBulk) {
                    long remSeconds = (cooldownEnd - now + 999L) / 1000L;
                    this.messenger.sendMessage(
                        sender,
                        config().messages().cooldownActive(),
                        "seconds", String.valueOf(remSeconds)
                    );
                }
                return false;
            }
        }

        TpaRequest existing = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId()).orElse(null);
        if (existing != null) {
            if (!existing.isExpired(config().requestTimeoutSeconds())) {
                if (!isBulk) {
                    this.messenger.sendMessage(
                        sender,
                        config().messages().alreadyHasPendingRequest(),
                        "target", target.getName()
                    );
                }
                return false;
            } else {
                this.repository.removeRequest(target.getUniqueId(), sender.getUniqueId());
            }
        }

        int maxRequests = config().maxPendingRequestsPerPlayer();
        if (maxRequests > 0) {
            Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
            int activeCount = 0;
            int timeoutSeconds = config().requestTimeoutSeconds();
            for (TpaRequest req : incoming) {
                if (!req.isExpired(timeoutSeconds)) {
                    activeCount++;
                }
            }
            if (activeCount >= maxRequests) {
                if (!isBulk) {
                    this.messenger.sendMessage(
                        sender,
                        config().messages().maxPendingRequestsReached(),
                        "target", target.getName()
                    );
                }
                return false;
            }
        }

        this.escrowManager.claimPendingRefunds(sender);

        if (this.repository.isAutoAcceptEnabled(target.getUniqueId())) {
            if (this.warmupManager.isPlayerInWarmup(sender.getUniqueId()) || this.warmupManager.isPlayerInWarmup(target.getUniqueId())) {
                return false;
            }
            double sendCost = this.economyService.getCost(sender, type);
            String timing = config().getNormalizedChargeTiming();
            if ("CHARGE_ON_SEND".equals(timing)) {
                if (isBulk && !this.economyService.has(sender, sendCost)) {
                    return false;
                }
                if (!this.economyService.processSendCost(sender, sendCost)) {
                    return false;
                }
            } else if ("CHARGE_ON_ACCEPT".equals(timing) || "CHARGE_ON_SUCCESS".equals(timing)) {
                if (sendCost > 0.0 && !this.economyService.has(sender, sendCost)) {
                    if (!isBulk) {
                        double balance = this.economyService.getBalance(sender);
                        TpaConfig cfg = config();
                        TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(sendCost));
                        TagResolver balResolver = Placeholder.unparsed("balance", this.economyService.format(balance));
                        this.messenger.sendMessage(sender, cfg.messages().insufficientFunds(), costResolver, balResolver);
                    }
                    return false;
                }
            }
            if (applyCooldown && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
                this.repository.setCooldownEnd(sender.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
            }
            TpaRequest req = new TpaRequest(sender.getUniqueId(), target.getUniqueId(), type, System.currentTimeMillis(), sendCost);
            this.repository.addRequest(req);
            this.messenger.sendMessage(sender, config().messages().requestAutoAcceptedSender(), "target", target.getName());
            final String senderName = sender.getName();
            final UUID senderId = sender.getUniqueId();
            final UUID targetId = target.getUniqueId();
            target.getScheduler().run(
                this.plugin,
                tTask -> {
                    if (target.isOnline()) {
                        this.messenger.sendMessage(target, config().messages().requestAutoAcceptedTarget(), "sender", senderName);
                        if (this.autoAcceptDispatcher != null) {
                            this.autoAcceptDispatcher.accept(target, senderName);
                        }
                    } else {
                        this.repository.removeRequest(targetId, senderId);
                        this.repository.setCooldownEnd(senderId, 0L);
                        this.escrowManager.refundSenderIfCharged(senderId, sendCost, "Target offline during auto-accept dispatch");
                        Player s = Bukkit.getPlayer(senderId);
                        if (s != null && s.isOnline()) {
                            s.getScheduler().run(this.plugin, sTask -> {
                                if (s.isOnline()) {
                                    this.messenger.sendMessage(s, config().messages().playerNotOnline(), "player", target.getName());
                                }
                            }, null);
                        }
                    }
                },
                null
            );
            return true;
        }

        double sendCost = this.economyService.getCost(sender, type);
        String timing = config().getNormalizedChargeTiming();
        if ("CHARGE_ON_SEND".equals(timing)) {
            if (isBulk && !this.economyService.has(sender, sendCost)) {
                return false;
            }
            if (!this.economyService.processSendCost(sender, sendCost)) {
                return false;
            }
        } else if ("CHARGE_ON_ACCEPT".equals(timing) || "CHARGE_ON_SUCCESS".equals(timing)) {
            if (sendCost > 0.0 && !this.economyService.has(sender, sendCost)) {
                if (!isBulk) {
                    double balance = this.economyService.getBalance(sender);
                    TpaConfig cfg = config();
                    TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(sendCost));
                    TagResolver balResolver = Placeholder.unparsed("balance", this.economyService.format(balance));
                    this.messenger.sendMessage(sender, cfg.messages().insufficientFunds(), costResolver, balResolver);
                }
                return false;
            }
        }

        TpaRequest request = new TpaRequest(
            sender.getUniqueId(),
            target.getUniqueId(),
            type,
            System.currentTimeMillis(),
            sendCost
        );

        this.repository.addRequest(request);

        if (applyCooldown && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            this.repository.setCooldownEnd(sender.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        }

        if (type == TpaType.TPA_TO) {
            this.messenger.sendMessage(
                sender,
                config().messages().senderTpaSent(),
                "target", target.getName(),
                "seconds", String.valueOf(config().requestTimeoutSeconds())
            );

            this.messenger.sendMessage(
                target,
                config().messages().targetTpaReceived(),
                "sender", sender.getName()
            );
        } else {
            this.messenger.sendMessage(
                sender,
                config().messages().senderTpaHereSent(),
                "target", target.getName(),
                "seconds", String.valueOf(config().requestTimeoutSeconds())
            );

            this.messenger.sendMessage(
                target,
                config().messages().targetTpaHereReceived(),
                "sender", sender.getName()
            );
        }
        return true;
    }
}
