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
    private final VaultTpaRefundProcessor refundProcessor;
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
        this.refundProcessor = new VaultTpaRefundProcessor(
            plugin, configSupplier, repositorySupplier, this, this.ioExecutor, logger, this.miniMessage, this.keyPendingRefund
        );
        setupVault();
    }

    private double roundCurrency(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return 0.0;
        }
        int digits = 2;
        if (this.vaultEconomy != null) {
            try {
                int frac = this.vaultEconomy.fractionalDigits();
                if (frac >= 0) {
                    digits = Math.min(6, frac);
                }
            } catch (Throwable ignored) {
            }
        }
        if (digits == 0) {
            return Math.floor(amount);
        }
        double factor = Math.pow(10.0, digits);
        return Math.round(amount * factor) / factor;
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
        if (player == null) {
            return false;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        try {
            double cleanAmount = roundCurrency(amount);
            if (this.vaultEconomy.has(player, cleanAmount)) {
                return true;
            }
            double balance = this.vaultEconomy.getBalance(player);
            return (balance + 1e-5) >= cleanAmount;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return true;
        }
        if (player == null) {
            return false;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        String pName = (player.getName() != null) ? player.getName() : player.getUniqueId().toString();
        try {
            EconomyResponse resp = this.vaultEconomy.withdrawPlayer(player, roundCurrency(amount));
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.warn("Vault economy withdraw failed for player {} (UUID: {}): {}", pName, player.getUniqueId(), t.getMessage());
            return false;
        }
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return true;
        }
        if (player == null) {
            return false;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        String pName = (player.getName() != null) ? player.getName() : player.getUniqueId().toString();
        try {
            EconomyResponse resp = this.vaultEconomy.depositPlayer(player, roundCurrency(amount));
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.warn("Vault economy deposit failed for player {} (UUID: {}): {}", pName, player.getUniqueId(), t.getMessage());
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
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
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

        TpaConfig cfg = this.configSupplier.get();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver costResolver = Placeholder.unparsed("cost", format(cost));

        if (!has(player, cost)) {
            double balance = getBalance(player);
            TagResolver balResolver = Placeholder.unparsed("balance", format(balance));
            player.sendMessage(this.miniMessage.deserialize(
                MessageFormatter.toMiniMessage(cfg.messages().insufficientFunds()),
                TagResolver.resolver(prefixResolver, costResolver, balResolver)
            ));
            return false;
        }

        if (withdraw(player, cost)) {
            player.sendMessage(this.miniMessage.deserialize(
                MessageFormatter.toMiniMessage(cfg.messages().moneyWithdrawn()),
                TagResolver.resolver(prefixResolver, costResolver)
            ));
            return true;
        }

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
                double balance = getBalance(player);
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
        this.refundProcessor.processRefund(player, amount, reason);
    }

    @Override
    public void processReward(Player target, OfflinePlayer sender, double cost) {
        this.refundProcessor.processReward(target, sender, cost);
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
