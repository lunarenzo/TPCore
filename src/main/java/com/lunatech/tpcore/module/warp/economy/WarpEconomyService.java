package com.lunatech.tpcore.module.warp.economy;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Service boundary interface for Warp module economy operations (Vault / VaultUnlocked).
 */
public interface WarpEconomyService {
    record Payment(UUID playerId, double amount, boolean successful, UUID ticket) {
        public Payment(UUID playerId, double amount, boolean successful) { this(playerId, amount, successful, null); }
    }

    CompletableFuture<Payment> chargeExact(UUID playerId, double amount);

    CompletableFuture<Boolean> validateExact(UUID playerId, double amount);

    CompletableFuture<Boolean> refundExact(UUID playerId, double amount);
    CompletableFuture<Boolean> settle(Payment payment, boolean refund);

    CompletableFuture<Void> close();

    boolean isAvailable();

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
