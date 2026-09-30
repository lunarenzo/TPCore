package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Manages player escrow lifecycles, timing-based charges, refunds, and pending claim recovery.
 */
final class TpaEscrowManager {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaRepository repository;
    private final TpaEconomyService economyService;
    private final TpaMessenger messenger;
    private final NamespacedKey keyPendingRefund;
    private final Set<UUID> inFlightRefundClaims = ConcurrentHashMap.newKeySet();

    TpaEscrowManager(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaRepository repository,
                     TpaEconomyService economyService, TpaMessenger messenger) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repository = repository;
        this.economyService = economyService;
        this.messenger = messenger;
        this.keyPendingRefund = (plugin != null) ? new NamespacedKey(plugin, "tpa_pending_refund") : null;
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    void claimPendingRefunds(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!config().economyEnabled() || !this.economyService.isAvailable()) {
            return;
        }
        final UUID playerId = player.getUniqueId();
        if (!this.inFlightRefundClaims.add(playerId)) {
            return;
        }
        player.getScheduler().run(
            this.plugin,
            claimTask -> {
                if (!player.isOnline()) {
                    this.inFlightRefundClaims.remove(playerId);
                    return;
                }
                PersistentDataContainer pdc = player.getPersistentDataContainer();
                boolean hasPdc = this.keyPendingRefund != null && pdc.has(this.keyPendingRefund, PersistentDataType.DOUBLE);
                boolean hasRepo = this.repository.hasPendingRefund(playerId);
                if (!hasPdc && !hasRepo) {
                    this.inFlightRefundClaims.remove(playerId);
                    return;
                }

                double totalPending = 0.0;
                if (hasPdc) {
                    Double pending = pdc.get(this.keyPendingRefund, PersistentDataType.DOUBLE);
                    if (pending != null && !Double.isNaN(pending) && !Double.isInfinite(pending) && pending > 0.0) {
                        totalPending += pending;
                    }
                    pdc.remove(this.keyPendingRefund);
                }
                double repoPending = this.repository.consumePendingRefund(playerId);
                if (!Double.isNaN(repoPending) && !Double.isInfinite(repoPending) && repoPending > 0.0) {
                    totalPending += repoPending;
                }

                if (totalPending > 0.0) {
                    final double finalAmount = Math.round(totalPending * 100.0) / 100.0;
                    this.economyService.depositAsync(player, finalAmount).whenComplete((success, ex) -> {
                        try {
                            if (ex == null && Boolean.TRUE.equals(success)) {
                                Player onlinePlayer = Bukkit.getPlayer(playerId);
                                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                    onlinePlayer.getScheduler().run(
                                        this.plugin,
                                        t -> {
                                            if (onlinePlayer.isOnline()) {
                                                TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(finalAmount));
                                                this.messenger.sendMessage(onlinePlayer, config().messages().moneyRefunded(), costResolver);
                                            }
                                        },
                                        null
                                    );
                                }
                            } else {
                                if (ex != null) {
                                    this.plugin.getSLF4JLogger().warn("depositAsync failed exceptionally for player {} during pending refund claim", playerId, ex);
                                }
                                this.repository.addPendingRefund(playerId, finalAmount);
                            }
                        } finally {
                            this.inFlightRefundClaims.remove(playerId);
                        }
                    });
                } else {
                    this.inFlightRefundClaims.remove(playerId);
                }
            },
            () -> this.inFlightRefundClaims.remove(playerId)
        );
    }

    void refundRequestSenderIfCharged(TpaRequest req, String reason) {
        if (req == null || req.cost() <= 0.0) {
            return;
        }
        String timing = config().getNormalizedChargeTiming();
        if ("CHARGE_ON_SEND".equals(timing)) {
            OfflinePlayer senderOp = TpaPlayerResolver.resolveOfflinePlayer(req.senderId());
            this.economyService.processRefund(senderOp, req.cost(), reason);
        }
    }

    void refundSenderIfCharged(UUID senderId, double cost, String reason) {
        if (senderId == null || cost <= 0.0) {
            return;
        }
        String timing = config().getNormalizedChargeTiming();
        if ("CHARGE_ON_SEND".equals(timing) || "CHARGE_ON_ACCEPT".equals(timing)) {
            refundSenderAfterCharge(senderId, cost, reason);
        }
    }

    void refundSenderAfterCharge(UUID senderId, double cost, String reason) {
        if (senderId == null || cost <= 0.0) {
            return;
        }
        OfflinePlayer senderOp = TpaPlayerResolver.resolveOfflinePlayer(senderId);
        this.economyService.processRefund(senderOp, cost, reason);
    }

    void evict(UUID playerId) {
        this.inFlightRefundClaims.remove(playerId);
    }

    void clear() {
        this.inFlightRefundClaims.clear();
    }
}
