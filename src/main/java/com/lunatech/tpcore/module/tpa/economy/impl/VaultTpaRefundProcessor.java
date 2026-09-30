package com.lunatech.tpcore.module.tpa.economy.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Handles asynchronous economy refund and reward dispatches with offline player safety guards.
 */
final class VaultTpaRefundProcessor {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final Supplier<TpaRepository> repositorySupplier;
    private final TpaEconomyService economyService;
    private final ExecutorService ioExecutor;
    private final Logger logger;
    private final MiniMessage miniMessage;
    private final NamespacedKey keyPendingRefund;

    VaultTpaRefundProcessor(
        JavaPlugin plugin,
        Supplier<TpaConfig> configSupplier,
        Supplier<TpaRepository> repositorySupplier,
        TpaEconomyService economyService,
        ExecutorService ioExecutor,
        Logger logger,
        MiniMessage miniMessage,
        NamespacedKey keyPendingRefund
    ) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repositorySupplier = repositorySupplier;
        this.economyService = economyService;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
        this.miniMessage = miniMessage;
        this.keyPendingRefund = keyPendingRefund;
    }

    void processRefund(OfflinePlayer player, double amount, String reason) {
        if (player == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return;
        }
        final double cleanAmount = Math.round(amount * 100.0) / 100.0;
        if (cleanAmount <= 0.0) {
            return;
        }
        final String playerName = (player.getName() != null) ? player.getName() : player.getUniqueId().toString();

        this.ioExecutor.submit(() -> {
            boolean success = false;
            try {
                if (player.getName() != null || player.hasPlayedBefore()) {
                    success = this.economyService.deposit(player, cleanAmount);
                }
            } catch (Throwable t) {
                this.logger.warn("Vault economy deposit error during refund for {} ({}): {}", playerName, player.getUniqueId(), t.getMessage());
            }

            if (success) {
                if (player.isOnline() && player.getPlayer() != null) {
                    Player p = player.getPlayer();
                    p.getScheduler().run(this.plugin, t -> {
                        if (p.isOnline()) {
                            TpaConfig cfg = this.configSupplier.get();
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", this.economyService.format(cleanAmount));
                            p.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().moneyRefunded()),
                                TagResolver.resolver(prefixResolver, costResolver)
                            ));
                        }
                    }, null);
                }
            } else {
                TpaRepository repo = (this.repositorySupplier != null) ? this.repositorySupplier.get() : null;
                if (repo != null) {
                    repo.addPendingRefund(player.getUniqueId(), cleanAmount);
                    this.logger.warn("Offline deposit of {} failed for {}. Queued into repository pending refunds.", this.economyService.format(cleanAmount), playerName);
                } else if (player.isOnline() && player.getPlayer() != null) {
                    Player onlinePlayer = player.getPlayer();
                    onlinePlayer.getScheduler().run(this.plugin, t -> queuePendingRefund(onlinePlayer, cleanAmount), null);
                } else {
                    this.logger.warn("Failed to deposit refund of {} for offline player {}. No repository available to queue refund.", this.economyService.format(cleanAmount), playerName);
                }
            }
        });
    }

    void processReward(Player target, OfflinePlayer sender, double cost) {
        if (target == null || Double.isNaN(cost) || Double.isInfinite(cost) || cost <= 0.0 || !this.economyService.isAvailable()) {
            return;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || cfg.targetRewardPercent() <= 0.0) {
            return;
        }
        double pct = Math.max(0.0, Math.min(100.0, cfg.targetRewardPercent()));
        double rawReward = (cost * pct) / 100.0;
        final double reward = Math.round(rawReward * 100.0) / 100.0;
        if (reward <= 0.0) {
            return;
        }

        final UUID targetId = target.getUniqueId();
        final OfflinePlayer initialTarget = target;
        final String senderDisplayName = (sender != null && sender.getName() != null) ? sender.getName() : "Player";

        this.ioExecutor.submit(() -> {
            OfflinePlayer offlineTarget = Bukkit.getPlayer(targetId);
            if (offlineTarget == null) {
                offlineTarget = initialTarget;
            }
            if (this.economyService.deposit(offlineTarget, reward)) {
                Player onlineTarget = Bukkit.getPlayer(targetId);
                if (onlineTarget != null && onlineTarget.isOnline()) {
                    onlineTarget.getScheduler().run(this.plugin, t -> {
                        if (onlineTarget.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver rewardResolver = Placeholder.unparsed("reward", this.economyService.format(reward));
                            TagResolver senderResolver = Placeholder.unparsed("sender", senderDisplayName);
                            onlineTarget.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().targetRewarded()),
                                TagResolver.resolver(prefixResolver, rewardResolver, senderResolver)
                            ));
                        }
                    }, null);
                }
            } else {
                TpaRepository repo = (this.repositorySupplier != null) ? this.repositorySupplier.get() : null;
                if (repo != null) {
                    repo.addPendingRefund(targetId, reward);
                    this.logger.warn("Vault economy deposit error during reward of {} for player {}. Queued into repository pending refunds.", this.economyService.format(reward), targetId);
                } else {
                    this.logger.warn("Vault economy deposit error during reward of {} for player {}", this.economyService.format(reward), targetId);
                }
            }
        });
    }

    void queuePendingRefund(Player player, double amount) {
        if (player == null || !player.isOnline() || this.keyPendingRefund == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return;
        }
        try {
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            Double existing = pdc.get(this.keyPendingRefund, PersistentDataType.DOUBLE);
            double newTotal = Math.round(((existing != null ? existing : 0.0) + amount) * 100.0) / 100.0;
            pdc.set(this.keyPendingRefund, PersistentDataType.DOUBLE, newTotal);
        } catch (Throwable ignored) {
        }
    }
}
