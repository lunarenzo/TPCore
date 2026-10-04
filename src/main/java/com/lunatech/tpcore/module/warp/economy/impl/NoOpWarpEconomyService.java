package com.lunatech.tpcore.module.warp.economy.impl;

import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Null-object fallback implementation of WarpEconomyService used when Vault is absent or economy is disabled.
 */
public final class NoOpWarpEconomyService implements WarpEconomyService {

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
    public CompletableFuture<Boolean> withdrawAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> depositAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.completedFuture(true);
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
    public double getWarpCost(Player player) {
        return 0.0;
    }

    @Override
    public double getSetWarpCost(Player player) {
        return 0.0;
    }

    @Override
    public CompletableFuture<Boolean> validateWarpFundsAsync(Player player) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> processWarpCostAsync(Player player) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> chargeSuccessAsync(Player player) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> processSetWarpCostAsync(Player player) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void processRefund(OfflinePlayer player, double amount) {
        // No-Op
    }
}
