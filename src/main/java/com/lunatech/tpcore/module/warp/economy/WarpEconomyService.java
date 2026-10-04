package com.lunatech.tpcore.module.warp.economy;

import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Service boundary interface for Warp module economy operations (Vault / VaultUnlocked).
 */
public interface WarpEconomyService {

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

    double getWarpCost(Player player);

    double getSetWarpCost(Player player);

    CompletableFuture<Boolean> validateWarpFundsAsync(Player player);

    CompletableFuture<Boolean> processWarpCostAsync(Player player);

    CompletableFuture<Boolean> chargeSuccessAsync(Player player);

    CompletableFuture<Boolean> processSetWarpCostAsync(Player player);

    void processRefund(OfflinePlayer player, double amount);

    default void shutdown() {}
}
