package com.lunatech.tpcore.module.spawn.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public interface SpawnEconomyService {

    boolean isAvailable();

    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);

    default CompletableFuture<Boolean> withdrawAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> withdraw(player, amount));
    }

    default CompletableFuture<Boolean> depositAsync(OfflinePlayer player, double amount) {
        return CompletableFuture.supplyAsync(() -> deposit(player, amount));
    }

    double getBalance(OfflinePlayer player);

    String format(double amount);

    double getCost(Player player);

    CompletableFuture<Boolean> validateFundsAsync(Player player);

    CompletableFuture<Boolean> processTeleportCostAsync(Player player);

    CompletableFuture<Boolean> chargeSuccessAsync(Player player);

    void processRefund(OfflinePlayer player, double amount);

    default void shutdown() {}
}
