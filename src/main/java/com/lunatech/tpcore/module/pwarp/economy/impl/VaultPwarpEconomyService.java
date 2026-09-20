package com.lunatech.tpcore.module.pwarp.economy.impl;

import com.lunatech.tpcore.module.pwarp.economy.PwarpEconomyService;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.slf4j.Logger;

/**
 * Vault / VaultUnlocked economy provider bridge.
 */
public final class VaultPwarpEconomyService implements PwarpEconomyService {

    private final Logger logger;
    private Economy vaultEconomy;

    public VaultPwarpEconomyService(Logger logger) {
        this.logger = logger;
        setupVault();
    }

    private synchronized void setupVault() {
        try {
            if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null || Bukkit.getPluginManager().getPlugin("Vault") == null) {
                this.logger.info("Vault plugin not found. Pwarp economy features will be disabled/free.");
                return;
            }
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.vaultEconomy = rsp.getProvider();
                this.logger.info("Successfully hooked into Vault Economy provider: {}", this.vaultEconomy.getName());
            } else {
                this.logger.warn("Vault plugin found, but no Economy provider (e.g. VaultUnlocked) is registered.");
            }
        } catch (Throwable t) {
            this.logger.info("Vault plugin environment not active. Pwarp economy features will be disabled/free.");
        }
    }

    @Override
    public boolean isAvailable() {
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
        return this.vaultEconomy.has(player, amount);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0.0) {
            return true;
        }
        if (!isAvailable()) {
            return true;
        }
        EconomyResponse resp = this.vaultEconomy.withdrawPlayer(player, amount);
        return resp != null && resp.transactionSuccess();
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0.0) {
            return true;
        }
        if (!isAvailable()) {
            return true;
        }
        EconomyResponse resp = this.vaultEconomy.depositPlayer(player, amount);
        return resp != null && resp.transactionSuccess();
    }

    @Override
    public String format(double amount) {
        if (isAvailable()) {
            return this.vaultEconomy.format(amount);
        }
        return String.format("$%.2f", amount);
    }
}
