package com.lunatech.tpcore.module.spawn.economy.impl;

import com.lunatech.tpcore.module.spawn.economy.SpawnEconomyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public final class NoOpSpawnEconomyService implements SpawnEconomyService {

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
        return String.format("$%.2f", amount);
    }

    @Override
    public double getCost(Player player) {
        return 0.0;
    }

    @Override
    public CompletableFuture<Boolean> validateFundsAsync(Player player) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> processTeleportCostAsync(Player player) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> chargeSuccessAsync(Player player) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void processRefund(OfflinePlayer player, double amount) {}
}
