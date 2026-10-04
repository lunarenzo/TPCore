package com.lunatech.tpcore.module.warp.economy.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.util.MessageFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.slf4j.Logger;

public final class VaultWarpEconomyService implements WarpEconomyService {

    private final Plugin plugin;
    private final Supplier<WarpConfig> configSupplier;
    private final Logger logger;
    private final MiniMessage miniMessage;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private Economy vaultEconomy;
    private long lastVaultCheckTimestamp = 0L;
    private boolean loggedVaultUnavailable = false;

    public VaultWarpEconomyService(Plugin plugin, Supplier<WarpConfig> configSupplier, Logger logger) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.logger = logger;
        this.miniMessage = MiniMessage.miniMessage();
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
            } catch (Throwable ignored) {}
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
                    this.logger.info("Vault/VaultUnlocked plugin not found. Warp economy features will be disabled/free.");
                    this.loggedVaultUnavailable = true;
                }
                this.vaultEconomy = null;
                return;
            }
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.vaultEconomy = rsp.getProvider();
                this.loggedVaultUnavailable = false;
                this.logger.info("Successfully hooked into Vault Economy provider for Warp: {}", this.vaultEconomy.getName());
            } else {
                this.vaultEconomy = null;
                if (!this.loggedVaultUnavailable) {
                    this.logger.warn("Vault plugin found, but no Economy provider is registered.");
                    this.loggedVaultUnavailable = true;
                }
            }
        } catch (Throwable t) {
            this.vaultEconomy = null;
            if (!this.loggedVaultUnavailable) {
                this.logger.info("Vault plugin environment not active. Warp economy features will be disabled/free.");
                this.loggedVaultUnavailable = true;
            }
        }
    }

    @Override
    public boolean isAvailable() {
        WarpConfig cfg = this.configSupplier.get();
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
        WarpConfig cfg = this.configSupplier.get();
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
        WarpConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.withdrawPlayer(player, roundCurrency(amount));
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.warn("Vault economy withdraw failed for player {} (UUID: {}): {}", player.getName(), player.getUniqueId(), t.getMessage());
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
        WarpConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.depositPlayer(player, roundCurrency(amount));
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.warn("Vault economy deposit failed for player {} (UUID: {}): {}", player.getName(), player.getUniqueId(), t.getMessage());
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
            } catch (Throwable ignored) {}
        }
        return String.format(Locale.ROOT, "$%.2f", clean);
    }

    @Override
    public double getWarpCost(Player player) {
        if (player == null || player.hasPermission(Permissions.WARP_ADMIN) || player.hasPermission(Permissions.WARP_BYPASS_COST) || player.hasPermission(Permissions.WARP_BYPASS_COST_WARP)) {
            return 0.0;
        }
        WarpConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return 0.0;
        }
        return cfg.warpCost();
    }

    @Override
    public double getSetWarpCost(Player player) {
        if (player == null || player.hasPermission(Permissions.WARP_ADMIN) || player.hasPermission(Permissions.WARP_BYPASS_COST) || player.hasPermission(Permissions.WARP_BYPASS_COST_SETWARP)) {
            return 0.0;
        }
        WarpConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return 0.0;
        }
        return cfg.setWarpCost();
    }

    @Override
    public CompletableFuture<Boolean> validateWarpFundsAsync(Player player) {
        double cost = getWarpCost(player);
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            WarpConfig cfg = this.configSupplier.get();
            if (!has(player, cost)) {
                double balance = getBalance(player);
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            TagResolver balResolver = Placeholder.unparsed("balance", format(balance));
                            player.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().insufficientFunds()),
                                TagResolver.resolver(prefixResolver, costResolver, balResolver)
                            ));
                        }
                    }, null);
                }
                return false;
            }
            return true;
        }, this.ioExecutor);
    }

    @Override
    public CompletableFuture<Boolean> processWarpCostAsync(Player player) {
        double cost = getWarpCost(player);
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            WarpConfig cfg = this.configSupplier.get();
            if (!has(player, cost)) {
                double balance = getBalance(player);
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            TagResolver balResolver = Placeholder.unparsed("balance", format(balance));
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
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            player.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().costDeducted()),
                                TagResolver.resolver(prefixResolver, costResolver)
                            ));
                        }
                    }, null);
                }
                return true;
            }

            return false;
        }, this.ioExecutor);
    }

    @Override
    public CompletableFuture<Boolean> chargeSuccessAsync(Player player) {
        double cost = getWarpCost(player);
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            WarpConfig cfg = this.configSupplier.get();
            if (withdraw(player, cost)) {
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            player.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().costDeducted()),
                                TagResolver.resolver(prefixResolver, costResolver)
                            ));
                        }
                    }, null);
                }
                return true;
            }
            return false;
        }, this.ioExecutor);
    }

    @Override
    public CompletableFuture<Boolean> processSetWarpCostAsync(Player player) {
        double cost = getSetWarpCost(player);
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            WarpConfig cfg = this.configSupplier.get();
            if (!has(player, cost)) {
                double balance = getBalance(player);
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            TagResolver balResolver = Placeholder.unparsed("balance", format(balance));
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
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(cost));
                            player.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().costDeducted()),
                                TagResolver.resolver(prefixResolver, costResolver)
                            ));
                        }
                    }, null);
                }
                return true;
            }

            return false;
        }, this.ioExecutor);
    }

    @Override
    public void processRefund(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0.0 || !isAvailable()) {
            return;
        }
        depositAsync(player, amount).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                Player online = player.getPlayer();
                if (online != null && online.isOnline()) {
                    online.getScheduler().run(this.plugin, task -> {
                        if (online.isOnline()) {
                            WarpConfig cfg = this.configSupplier.get();
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", format(amount));
                            online.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().costRefunded()),
                                TagResolver.resolver(prefixResolver, costResolver)
                            ));
                        }
                    }, null);
                }
            }
        });
    }

    @Override
    public void shutdown() {
        this.ioExecutor.shutdown();
        try {
            if (!this.ioExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                List<Runnable> dropped = this.ioExecutor.shutdownNow();
                if (dropped != null && !dropped.isEmpty()) {
                    this.logger.warn("Warp Economy I/O executor forced shutdown; {} pending tasks dropped.", dropped.size());
                }
            }
        } catch (InterruptedException e) {
            this.ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
