package com.lunatech.tpcore.module.tpa.economy.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaType;
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

import java.util.function.Supplier;

/**
 * Concrete Vault / VaultUnlocked bridge implementation for TPA module economy operations.
 */
public final class VaultTpaEconomyService implements TpaEconomyService {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final Logger logger;
    private final MiniMessage miniMessage;
    private final NamespacedKey keyPendingRefund;
    private Economy vaultEconomy;

    public VaultTpaEconomyService(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, Logger logger) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.logger = logger;
        this.miniMessage = MiniMessage.miniMessage();
        this.keyPendingRefund = (plugin != null) ? new NamespacedKey(plugin, "tpa_pending_refund") : null;
        setupVault();
    }

    private synchronized void setupVault() {
        try {
            if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null || Bukkit.getPluginManager().getPlugin("Vault") == null) {
                this.logger.info("Vault plugin not found. TPA economy features will be disabled/free.");
                return;
            }
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.vaultEconomy = rsp.getProvider();
                this.logger.info("Successfully hooked into Vault Economy provider for TPA: {}", this.vaultEconomy.getName());
            } else {
                this.logger.warn("Vault plugin found, but no Economy provider (e.g. VaultUnlocked) is registered.");
            }
        } catch (Throwable t) {
            this.logger.info("Vault plugin environment not active. TPA economy features will be disabled/free.");
        }
    }

    @Override
    public boolean isAvailable() {
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || !cfg.economyEnabled()) {
            return false;
        }
        if (this.vaultEconomy == null) {
            setupVault();
        }
        return this.vaultEconomy != null && this.vaultEconomy.isEnabled();
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (amount <= 0.0) {
            return true;
        }
        if (!isAvailable()) {
            return true;
        }
        try {
            return this.vaultEconomy.has(player, amount);
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0.0) {
            return true;
        }
        if (!isAvailable()) {
            return true;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.withdrawPlayer(player, amount);
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.error("Vault economy withdraw failed for player {}", player.getName(), t);
            return false;
        }
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0.0) {
            return true;
        }
        if (!isAvailable()) {
            return true;
        }
        try {
            EconomyResponse resp = this.vaultEconomy.depositPlayer(player, amount);
            return resp != null && resp.transactionSuccess();
        } catch (Throwable t) {
            this.logger.error("Vault economy deposit failed for player {}", player.getName(), t);
            return false;
        }
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null || !isAvailable()) {
            return 0.0;
        }
        try {
            return this.vaultEconomy.getBalance(player);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    @Override
    public String format(double amount) {
        if (isAvailable()) {
            try {
                return this.vaultEconomy.format(amount);
            } catch (Throwable ignored) {
            }
        }
        return String.format("$%.2f", amount);
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
        if (cost <= 0.0) {
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
        return false;
    }

    @Override
    public void processRefund(OfflinePlayer player, double amount, String reason) {
        if (player == null || amount <= 0.0 || !isAvailable()) {
            return;
        }
        boolean success = deposit(player, amount);
        if (!success && player.isOnline() && player.getPlayer() != null) {
            // Queue pending refund in PDC if online deposit failed
            queuePendingRefund(player.getPlayer(), amount);
        } else if (!success && keyPendingRefund != null) {
            this.logger.warn("Failed to deposit refund of {} for offline player {}. Will attempt upon join.", format(amount), player.getName());
        }

        if (success && player.isOnline() && player.getPlayer() != null) {
            Player p = player.getPlayer();
            TpaConfig cfg = this.configSupplier.get();
            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
            TagResolver costResolver = Placeholder.unparsed("cost", format(amount));
            p.sendMessage(this.miniMessage.deserialize(
                MessageFormatter.toMiniMessage(cfg.messages().moneyRefunded()),
                TagResolver.resolver(prefixResolver, costResolver)
            ));
        }
    }

    @Override
    public void processReward(Player target, Player sender, double cost) {
        if (target == null || cost <= 0.0 || !isAvailable()) {
            return;
        }
        TpaConfig cfg = this.configSupplier.get();
        if (cfg == null || cfg.targetRewardPercent() <= 0.0) {
            return;
        }
        double pct = Math.max(0.0, Math.min(100.0, cfg.targetRewardPercent()));
        double reward = (cost * pct) / 100.0;
        if (reward > 0.0 && deposit(target, reward)) {
            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
            TagResolver rewardResolver = Placeholder.unparsed("reward", format(reward));
            TagResolver senderResolver = Placeholder.unparsed("sender", (sender != null) ? sender.getName() : "Player");
            target.sendMessage(this.miniMessage.deserialize(
                MessageFormatter.toMiniMessage(cfg.messages().targetRewarded()),
                TagResolver.resolver(prefixResolver, rewardResolver, senderResolver)
            ));
        }
    }

    private void queuePendingRefund(Player player, double amount) {
        if (player == null || keyPendingRefund == null) {
            return;
        }
        try {
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            Double existing = pdc.get(keyPendingRefund, PersistentDataType.DOUBLE);
            double newTotal = (existing != null ? existing : 0.0) + amount;
            pdc.set(keyPendingRefund, PersistentDataType.DOUBLE, newTotal);
        } catch (Throwable ignored) {
        }
    }
}
