package com.lunatech.tpcore.module.warp.economy.impl;

import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Null-object fallback implementation of WarpEconomyService used when Vault is absent or economy is disabled.
 */
public final class NoOpWarpEconomyService implements WarpEconomyService {
    @Override public WarpDiagnostics.Economy diagnostics() {
        return new WarpDiagnostics.Economy(false, false, false, 0, 0, 0);
    }
    @Override public CompletableFuture<WarpDiagnostics.PaymentPage> paymentIntents(int page, int pageSize) {
        return CompletableFuture.completedFuture(new WarpDiagnostics.PaymentPage(1, 1, 0, List.of()));
    }
    @Override public CompletableFuture<Payment> chargeExact(UUID id, double amount) {
        return CompletableFuture.completedFuture(new Payment(id, 0.0, true));
    }
    @Override public CompletableFuture<Boolean> validateExact(UUID id, double amount) {
        return CompletableFuture.completedFuture(true);
    }
    @Override public CompletableFuture<Boolean> refundExact(UUID id, double amount) {
        return CompletableFuture.completedFuture(true);
    }
    @Override public CompletableFuture<Void> close() { return CompletableFuture.completedFuture(null); }
    @Override public CompletableFuture<Boolean> settle(Payment payment, boolean refund) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public boolean isAvailable() {
        return false;
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
