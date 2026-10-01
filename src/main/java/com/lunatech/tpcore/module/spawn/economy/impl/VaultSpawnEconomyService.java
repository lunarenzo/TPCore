package com.lunatech.tpcore.module.spawn.economy.impl;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.spawn.economy.SpawnEconomyService;
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class VaultSpawnEconomyService implements SpawnEconomyService {

    private final JavaPlugin plugin;
    private final Supplier<SpawnConfig> configSupplier;
    private final Logger logger;
    private final MiniMessage miniMessage;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private Economy vaultEconomy;
    private long lastVaultCheckTimestamp = 0L;
    private boolean loggedVaultUnavailable = false;

    public VaultSpawnEconomyService(JavaPlugin plugin, Supplier<SpawnConfig> configSupplier, Logger logger) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.logger = logger;
        this.miniMessage = MiniMessage.miniMessage();
        this.setupVault();
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
                    this.logger.info("Vault/VaultUnlocked plugin not found. Spawn economy features will be disabled/free.");
                    this.loggedVaultUnavailable = true;
                }
                this.vaultEconomy = null;
                return;
            }
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.vaultEconomy = rsp.getProvider();
                this.loggedVaultUnavailable = false;
                this.logger.info("Successfully hooked into Vault Economy provider for Spawn: {}", this.vaultEconomy.getName());
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
                this.logger.info("Vault plugin environment not active. Spawn economy features will be disabled/free.");
                this.loggedVaultUnavailable = true;
            }
        }
    }

    @Override
    public boolean isAvailable() {
        SpawnConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled()) {
            return false;
        }
        if (this.vaultEconomy == null || !this.vaultEconomy.isEnabled()) {
            long now = System.currentTimeMillis();
            if (now - this.lastVaultCheckTimestamp > 10_000L) {
                this.setupVault();
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
        SpawnConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        try {
            double cleanAmount = this.roundCurrency(amount);
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
        SpawnConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.withdrawPlayer(player, this.roundCurrency(amount));
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
        SpawnConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return true;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.depositPlayer(player, this.roundCurrency(amount));
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.warn("Vault economy deposit failed for player {} (UUID: {}): {}", player.getName(), player.getUniqueId(), t.getMessage());
            return false;
        }
    }

    @Override
    public CompletableFuture<Boolean> withdrawAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> this.withdraw(player, amount), this.ioExecutor);
    }

    @Override
    public CompletableFuture<Boolean> depositAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> this.deposit(player, amount), this.ioExecutor);
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
            return this.roundCurrency(bal);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    @Override
    public String format(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "$0.00";
        }
        double clean = this.roundCurrency(amount);
        if (isAvailable()) {
            try {
                return this.vaultEconomy.format(clean);
            } catch (Throwable ignored) {}
        }
        return String.format(Locale.ROOT, "$%.2f", clean);
    }

    @Override
    public double getCost(Player player) {
        if (player == null || player.hasPermission(Permissions.SPAWN_BYPASS) || player.hasPermission(Permissions.SPAWN_BYPASS_COST)) {
            return 0.0;
        }
        SpawnConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled() || !isAvailable()) {
            return 0.0;
        }
        return cfg.spawnCost();
    }

    @Override
    public CompletableFuture<Boolean> validateFundsAsync(Player player) {
        double cost = this.getCost(player);
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            SpawnConfig cfg = this.configSupplier.get();
            if (!this.has(player, cost)) {
                double balance = this.getBalance(player);
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", this.format(cost));
                            TagResolver balResolver = Placeholder.unparsed("balance", this.format(balance));
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
    public CompletableFuture<Boolean> processTeleportCostAsync(Player player) {
        double cost = this.getCost(player);
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            SpawnConfig cfg = this.configSupplier.get();
            if (!this.has(player, cost)) {
                double balance = this.getBalance(player);
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", this.format(cost));
                            TagResolver balResolver = Placeholder.unparsed("balance", this.format(balance));
                            player.sendMessage(this.miniMessage.deserialize(
                                MessageFormatter.toMiniMessage(cfg.messages().insufficientFunds()),
                                TagResolver.resolver(prefixResolver, costResolver, balResolver)
                            ));
                        }
                    }, null);
                }
                return false;
            }

            if (this.withdraw(player, cost)) {
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", this.format(cost));
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
        double cost = this.getCost(player);
        if (player == null || !isAvailable() || cost <= 0.0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            SpawnConfig cfg = this.configSupplier.get();
            if (this.withdraw(player, cost)) {
                if (player.isOnline()) {
                    player.getScheduler().run(this.plugin, task -> {
                        if (player.isOnline()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", this.format(cost));
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
        this.depositAsync(player, amount).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                Player online = player.getPlayer();
                if (online != null && online.isOnline()) {
                    online.getScheduler().run(this.plugin, task -> {
                        if (online.isOnline()) {
                            SpawnConfig cfg = this.configSupplier.get();
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver costResolver = Placeholder.unparsed("cost", this.format(amount));
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
                    this.logger.warn("Spawn Economy I/O executor forced shutdown; {} pending tasks dropped.", dropped.size());
                }
            }
        } catch (InterruptedException e) {
            this.ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
