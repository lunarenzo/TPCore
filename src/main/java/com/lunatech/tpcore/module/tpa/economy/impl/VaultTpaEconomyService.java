package com.lunatech.tpcore.module.tpa.economy.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Concrete Vault / VaultUnlocked bridge implementation for TPA module economy operations.
 */
public final class VaultTpaEconomyService implements TpaEconomyService {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final Supplier<TpaRepository> repositorySupplier;
    private final Logger logger;
    private final MiniMessage miniMessage;
    private final NamespacedKey keyPendingRefund;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private Economy vaultEconomy;
    private long lastVaultCheckTimestamp = 0L;
    private boolean loggedVaultUnavailable = false;

    public VaultTpaEconomyService(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, Logger logger) {
        this(plugin, configSupplier, null, logger);
    }

    public VaultTpaEconomyService(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, Supplier<TpaRepository> repositorySupplier, Logger logger) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repositorySupplier = repositorySupplier;
        this.logger = logger;
        this.miniMessage = MiniMessage.miniMessage();
        this.keyPendingRefund = (plugin != null) ? new NamespacedKey(plugin, "tpa_pending_refund") : null;
        setupVault();
    }

    private static double roundCurrency(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return 0.0;
        }
        return Math.round(amount * 100.0) / 100.0;
    }

    private synchronized void setupVault() {
        this.lastVaultCheckTimestamp = System.currentTimeMillis();
        try {
            if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null || 
                (Bukkit.getPluginManager().getPlugin("Vault") == null && Bukkit.getPluginManager().getPlugin("VaultUnlocked") == null)) {
                if (!this.loggedVaultUnavailable) {
                    this.logger.info("Vault/VaultUnlocked plugin not found. TPA economy features will be disabled/free.");
                    this.loggedVaultUnavailable = true;
                }
                this.vaultEconomy = null;
                return;
            }
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.vaultEconomy = rsp.getProvider();
                this.loggedVaultUnavailable = false;
                this.logger.info("Successfully hooked into Vault Economy provider for TPA: {}", this.vaultEconomy.getName());
            } else {
                this.vaultEconomy = null;
                if (!this.loggedVaultUnavailable) {
                    this.logger.warn("Vault plugin found, but no Economy provider (e.g. VaultUnlocked) is registered.");
                    this.loggedVaultUnavailable = true;
                }
            }
        } catch (Throwable t) {
            this.vaultEconomy = null;
            if (!this.loggedVaultUnavailable) {
                this.logger.info("Vault plugin environment not active. TPA economy features will be disabled/free.");
                this.loggedVaultUnavailable = true;
            }
        }
    }

    @Override
    public boolean isAvailable() {
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled()) {
            return false;
        }
        if (this.vaultEconomy == null || !this.vaultEconomy.isEnabled()) {
            long now = System.currentTimeMillis();
            if (now - this.lastVaultCheckTimestamp > 10_000L) {
                setupVault();
            }
        }
        return this.vaultEconomy != null && this.vaultEconomy.isEnabled();
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return true;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled()) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            return this.vaultEconomy.has(player, roundCurrency(amount));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return true;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled()) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.withdrawPlayer(player, roundCurrency(amount));
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.error("Vault economy withdraw failed for player {}", player.getName(), t);
            return false;
        }
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return true;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled()) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.depositPlayer(player, roundCurrency(amount));
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.error("Vault economy deposit failed for player {}", player.getName(), t);
            return false;
        }
    }

    @Override
    public CompletableFuture<Boolean> withdrawAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> withdraw(player, amount), this.ioExecutor);
    }

    @Override
    public CompletableFuture<Boolean> depositAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> deposit(player, amount), this.ioExecutor);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null || !isAvailable()) {
            return 0.0;
        }
        try {
            double bal = this.vaultEconomy.getBalance(player);
            if (Double.isNaN(bal) || Double.isInfinite(bal)) {
                return 0.0;
            }
            return roundCurrency(bal);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    @Override
    public String format(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "$0.00";
        }
        double clean = roundCurrency(amount);
        if (isAvailable()) {
            try {
                return this.vaultEconomy.format(clean);
            } catch (Throwable ignored) {
            }
        }
        return String.format(Locale.ROOT, "$%.2f", clean);
    }

    @Override
    public double getCost(Player player, TpaType type) {
        if (player == null || player.hasPermission(Permissions.TPA_BYPASS_COST)) {
            return 0.0;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled()) {
            return 0.0;
        }
        return (type == TpaType.TPA_HERE) ? cfg.tpahereCost() : cfg.tpaCost();
    }

    @Override
    public boolean processSendCost(Player player, TpaType type) {
        if (player == null || !isAvailable()) {
            return true;
        }
        double cost = getCost(player, type);
        return processSendCost(player, cost);
    }

    @Override
    public boolean processSendCost(Player player, double cost) {
        if (player == null || !isAvailable() || cost <= 0.0) {
            return true;
        }

        if (!has(player, cost)) {
            double balance = 0.0;
            try {
                balance = this.vaultEconomy.getBalance(player);
            } catch (Throwable ignored) {
            }
            TpaConfig cfg = this.configSupplier.get();
            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
            TagResolver balResolver = Placeholder.unparsed("balance", format(balance));
            player.sendMessage(this.miniMessage.deserialize(
                MessageFormatter.toMiniMessage(cfg.messages().insufficientFunds()),
                TagResolver.resolver(prefixResolver, costResolver, balResolver)
            ));
            return false;
        }

        if (withdraw(player, cost)) {
            TpaConfig cfg = this.configSupplier.get();
            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
            player.sendMessage(this.miniMessage.deserialize(
                MessageFormatter.toMiniMessage(cfg.messages().moneyWithdrawn()),
                TagResolver.resolver(prefixResolver, costResolver)
            ));
            return true;
        }

        TpaConfig cfg = this.configSupplier.get();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
        player.sendMessage(this.miniMessage.deserialize(
            MessageFormatter.toMiniMessage(cfg.messages().teleportCancelledInsufficientFunds()),
            TagResolver.resolver(prefixResolver, costResolver)
        ));
        return false;
    }

    @Override
    public CompletableFuture<Boolean> processSendCostAsync(Player player, double cost) {
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!has(player, cost)) {
                double balance = 0.0;
                try {
                    balance = this.vaultEconomy.getBalance(player);
                } catch (Throwable ignored) {
                }
                TpaConfig cfg = this.configSupplier.get();
                final double finalBal = balance;
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, t -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            TagResolver balResolver = Placeholder.unparsed("balance", format(finalBal));
                            player.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().insufficientFunds()),
                                TagResolver.resolver(prefixResolver, costResolver, balResolver)
                            ));
                        }
                    }, null);
                }
                return false;
            }

            if (withdraw(player, cost)) {
                TpaConfig cfg = this.configSupplier.get();
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, t -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            player.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().moneyWithdrawn()),
                                TagResolver.resolver(prefixResolver, costResolver)
                            ));
                        }
                    }, null);
                }
                return true;
            }

            TpaConfig cfg = this.configSupplier.get();
            if (player.isOnline()) {
                player.getScheduler().run(this.plugin, t -> {
                    if (player.isOnline()) {
                        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                        TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                        player.sendMessage(this.miniMessage.deserialize(
                            MessageFormatter.toMiniMessage(cfg.messages().teleportCancelledInsufficientFunds()),
                            TagResolver.resolver(prefixResolver, costResolver)
                        ));
                    }
                }, null);
            }
            return false;
        }, this.ioExecutor);
    }

    @Override
    public CompletableFuture<Boolean> processSendCostAsync(Player player, TpaType type) {
        if (player == null || !isAvailable()) {
            return CompletableFuture.completedFuture(true);
        }
        double cost = getCost(player, type);
        return processSendCostAsync(player, cost);
    }

    @Override
    public void processRefund(OfflinePlayer player, double amount, String reason) {
        if (player == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return;
        }
        final double cleanAmount = roundCurrency(amount);
        if (cleanAmount <= 0.0) {
            return;
        }
        this.ioExecutor.submit(() -> {
            boolean success = false;
            try {
                success = deposit(player, cleanAmount);
            } catch (Throwable t) {
                this.logger.error("Vault economy deposit error during refund for {}", player.getName(), t);
            }
            if (success) {
                if (player.isOnline() && player.getPlayer() != null) {
                    Player p = player.getPlayer();
                    p.getScheduler().run(this.plugin, t -> {
                        if (p.isOnline()) {
                            TpaConfig cfg = this.configSupplier.get();
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cleanAmount));
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
                    this.logger.warn("Offline deposit of {} failed for {}. Queued into repository pending refunds.", format(cleanAmount), player.getName());
                } else if (player.isOnline() && player.getPlayer() != null) {
                    Player onlinePlayer = player.getPlayer();
                    onlinePlayer.getScheduler().run(this.plugin, t -> queuePendingRefund(onlinePlayer, cleanAmount), null);
                } else {
                    this.logger.warn("Failed to deposit refund of {} for offline player {}. No repository available to queue refund.", format(cleanAmount), player.getName());
                }
            }
        });
    }

    @Override
    public void processReward(Player target, Player sender, double cost) {
        if (target == null || Double.isNaN(cost) || Double.isInfinite(cost) || cost <= 0.0 || !isAvailable()) {
            return;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || cfg.targetRewardPercent() <= 0.0) {
            return;
        }
        double pct = Math.max(0.0, Math.min(100.0, cfg.targetRewardPercent()));
        double rawReward = (cost * pct) / 100.0;
        final double reward = roundCurrency(rawReward);
        if (reward <= 0.0) {
            return;
        }
        final UUID targetId = target.getUniqueId();
        final String senderDisplayName = (sender != null && sender.getName() != null) ? sender.getName() : "Player";
        final OfflinePlayer offlineTarget = target;
        this.ioExecutor.submit(() -> {
            if (deposit(offlineTarget, reward)) {
                Player onlineTarget = Bukkit.getPlayer(targetId);
                if (onlineTarget != null && onlineTarget.isOnline()) {
                    onlineTarget.getScheduler().run(this.plugin, t -> {
                        if (onlineTarget.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver rewardResolver = Placeholder.unparsed("reward", format(reward));
                            TagResolver senderResolver = Placeholder.unparsed("sender", senderDisplayName);
                            onlineTarget.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().targetRewarded()),
                                TagResolver.resolver(prefixResolver, rewardResolver, senderResolver)
                            ));
                        }
                    }, null);
                }
            }
        });
    }

    private void queuePendingRefund(Player player, double amount) {
        if (player == null || !player.isOnline() || keyPendingRefund == null || Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return;
        }
        try {
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            Double existing = pdc.get(keyPendingRefund, PersistentDataType.DOUBLE);
            double newTotal = roundCurrency((existing != null ? existing : 0.0) + amount);
            pdc.set(keyPendingRefund, PersistentDataType.DOUBLE, newTotal);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void shutdown() {
        this.ioExecutor.shutdown();
        try {
            if (!this.ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> dropped = this.ioExecutor.shutdownNow();
                if (dropped != null && !dropped.isEmpty()) {
                    this.logger.warn("Economy I/O executor forced shutdown; {} pending tasks dropped.", dropped.size());
                }
            }
        } catch (InterruptedException e) {
            this.ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
