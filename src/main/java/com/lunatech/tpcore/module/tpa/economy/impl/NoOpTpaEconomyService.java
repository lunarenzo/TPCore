package com.lunatech.tpcore.module.tpa.economy.impl;

import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import java.util.Locale;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Null-object fallback implementation of TpaEconomyService used when Vault is absent or economy is disabled.
 */
public final class NoOpTpaEconomyService implements TpaEconomyService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return true;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return true;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return true;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return 0.0;
    }

    @Override
    public String format(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0.0) {
            return "$0.00";
        }
        return String.format(Locale.ROOT, "$%.2f", Math.round(amount * 100.0) / 100.0);
    }

    @Override
    public double getCost(Player player, TpaType type) {
        return 0.0;
    }

    @Override
    public boolean processSendCost(Player player, TpaType type) {
        return true;
    }

    @Override
    public boolean processSendCost(Player player, double cost) {
        return true;
    }

    @Override
    public void processRefund(OfflinePlayer player, double amount, String reason) {
        // No-Op
    }

    @Override
    public void processReward(Player target, Player sender, double cost) {
        // No-Op
    }
}
