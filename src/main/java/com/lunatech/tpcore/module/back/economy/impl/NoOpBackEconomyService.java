package com.lunatech.tpcore.module.back.economy.impl;

import com.lunatech.tpcore.module.back.economy.BackEconomyService;
import com.lunatech.tpcore.module.back.model.BackCause;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class NoOpBackEconomyService implements BackEconomyService {

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
        return String.format(Locale.ROOT, "$%.2f", amount);
    }

    @Override
    public double getCost(Player player, BackCause cause) {
        return 0.0;
    }

    @Override
    public CompletableFuture<Boolean> validateFundsAsync(Player player, BackCause cause) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> processCostAsync(Player player, BackCause cause) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> chargeSuccessAsync(Player player, BackCause cause) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void processRefund(OfflinePlayer player, double amount) {
    }
}
