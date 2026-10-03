package com.lunatech.tpcore.module.back.economy;

import com.lunatech.tpcore.module.back.model.BackCause;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public interface BackEconomyService {

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

    double getCost(Player player, BackCause cause);

    default double getCost(Player player) {
        return getCost(player, BackCause.TELEPORT);
    }

    CompletableFuture<Boolean> validateFundsAsync(Player player, BackCause cause);

    CompletableFuture<Boolean> processCostAsync(Player player, BackCause cause);

    CompletableFuture<Boolean> chargeSuccessAsync(Player player, BackCause cause);

    void processRefund(OfflinePlayer player, double amount);

    default void shutdown() {}
}
