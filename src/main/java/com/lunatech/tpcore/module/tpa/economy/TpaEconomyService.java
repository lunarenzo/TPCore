package com.lunatech.tpcore.module.tpa.economy;

import com.lunatech.tpcore.module.tpa.model.TpaType;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Service boundary interface for TPA module economy operations.
 */
public interface TpaEconomyService {

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

    double getCost(Player player, TpaType type);

    boolean processSendCost(Player player, TpaType type);

    boolean processSendCost(Player player, double cost);

    default CompletableFuture<Boolean> processSendCostAsync(Player player, double cost) {
        return CompletableFuture.supplyAsync(() -> processSendCost(player, cost));
    }

    default CompletableFuture<Boolean> processSendCostAsync(Player player, TpaType type) {
        return CompletableFuture.supplyAsync(() -> processSendCost(player, type));
    }

    void processRefund(OfflinePlayer player, double amount, String reason);

    void processReward(Player target, OfflinePlayer sender, double cost);

    default void shutdown() {}
}
