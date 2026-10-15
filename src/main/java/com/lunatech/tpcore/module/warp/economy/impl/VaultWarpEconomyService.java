package com.lunatech.tpcore.module.warp.economy.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.repository.impl.WarpRefundJournal;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics;
import java.util.concurrent.RejectedExecutionException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

public final class VaultWarpEconomyService implements WarpEconomyService {
    private final Supplier<WarpConfig> config;
    private final Logger logger;
    private final String pluginName;
    private final VaultUnlockedWarpAdapter adapter;
    private final WarpRefundJournal journal;
    private final Semaphore admission;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean reconciliationRequired;
    private final CompletableFuture<Void> journalInitialized;

    public VaultWarpEconomyService(Plugin plugin, Supplier<WarpConfig> config, Logger logger) {
        this(plugin, config, logger, VaultUnlockedWarpAdapter.discover(plugin.getClass().getClassLoader()).orElse(null));
    }

    VaultWarpEconomyService(Plugin plugin, Supplier<WarpConfig> config, Logger logger, VaultUnlockedWarpAdapter adapter) {
        this.config = config;
        this.logger = logger;
        pluginName = plugin.getName();
        this.adapter = adapter;
        WarpConfig settings = config.get();
        admission = new Semaphore(settings.maxConcurrentOperations());
        journal = new WarpRefundJournal(plugin.getDataFolder().toPath().resolve("warps/refunds.properties"),
            settings.maxPlayerStateEntries(), settings.storageQueueCapacity());
        journalInitialized = journal.ready().thenRun(() -> {
            reconciliationRequired = journal.pending() > 0;
            if (reconciliationRequired) logger.error("Warp economy has {} unresolved payment intents in warps/refunds.properties; reconcile them before enabling more payments", journal.pending());
        });
        journalInitialized.exceptionally(failure -> { logger.error("Warp refund journal initialization failed", failure); return null; });
    }

    @Override public boolean isAvailable() { return adapter != null && !closed.get() && journalInitialized.isDone()
        && !journalInitialized.isCompletedExceptionally() && !reconciliationRequired; }

    @Override public WarpDiagnostics.Economy diagnostics() {
        return new WarpDiagnostics.Economy(isAvailable(), journal.isReady(), reconciliationRequired,
            pending.size(), journal.isReady() ? journal.pending() : -1, journal.capacity());
    }
    @Override public CompletableFuture<WarpDiagnostics.PaymentPage> paymentIntents(int page, int pageSize) {
        return journal.snapshot(page, pageSize);
    }

    private <T> CompletableFuture<T> admitted(Supplier<CompletableFuture<T>> action) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Warp economy is closed"));
        if (!admission.tryAcquire()) return CompletableFuture.failedFuture(new RejectedExecutionException("Warp economy is busy"));
        try {
            CompletableFuture<T> future = action.get();
            pending.add(future);
            future.whenComplete((value, failure) -> { pending.remove(future); admission.release(); });
            return future;
        } catch (RuntimeException failure) { admission.release(); return CompletableFuture.failedFuture(failure); }
    }

    @Override public CompletableFuture<Payment> chargeExact(UUID playerId, double amount) {
        if (amount <= 0.0) return CompletableFuture.completedFuture(new Payment(playerId, 0.0, true));
        if (!Double.isFinite(amount) || adapter == null) return CompletableFuture.failedFuture(new IllegalStateException("Native async economy provider is required"));
        return admitted(() -> journalInitialized.thenCompose(ignored -> {
            if (reconciliationRequired) return CompletableFuture.failedFuture(new IllegalStateException("Unresolved warp payments require reconciliation"));
            return adapter.has(pluginName, playerId, amount).thenCompose(enough -> {
                if (!enough) return CompletableFuture.completedFuture(new Payment(playerId, 0.0, false));
                return journal.record(playerId, amount).thenCompose(ticket -> {
                    return adapter.withdraw(pluginName, playerId, amount).thenCompose(paid -> {
                        if (paid < 0.0) return journal.complete(ticket).thenApply(done -> new Payment(playerId, 0.0, false));
                        return journal.update(ticket, playerId, paid).thenApply(done -> new Payment(playerId, paid, true, ticket));
                    });
                }).whenComplete((payment, failure) -> { if (failure != null) reconciliationRequired = true; })
                    .thenCompose(payment -> {
                        if (!payment.successful()) return CompletableFuture.failedFuture(new IllegalStateException("Economy provider rejected the withdrawal"));
                        if (Double.compare(payment.amount(), amount) != 0) return settle(payment, true).thenCompose(done ->
                            CompletableFuture.failedFuture(new IllegalStateException("Economy receipt amount differs from the quote")));
                        return CompletableFuture.completedFuture(payment);
                    });
            });
        }));
    }

    @Override public CompletableFuture<Boolean> settle(Payment payment, boolean refund) {
        if (payment == null || !payment.successful() || payment.amount() <= 0.0) {
            return payment != null && payment.ticket() != null
                ? journal.complete(payment.ticket()).thenApply(ignored -> true) : CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> transfer = refund
            ? adapter.deposit(pluginName, payment.playerId(), payment.amount()) : CompletableFuture.completedFuture(true);
        return transfer.thenCompose(success -> success && payment.ticket() != null
            ? journal.complete(payment.ticket()).thenApply(ignored -> true) : CompletableFuture.completedFuture(success))
            .whenComplete((success, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(success)) {
                    reconciliationRequired = true;
                    logger.error("Warp payment for {} amount {} requires reconciliation", payment.playerId(), payment.amount(), failure);
                }
            });
    }

    @Override public CompletableFuture<Boolean> validateExact(UUID playerId, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        if (adapter == null) return CompletableFuture.failedFuture(new IllegalStateException("Native async economy provider is required"));
        return admitted(() -> adapter.has(pluginName, playerId, amount));
    }

    @Override public CompletableFuture<Boolean> refundExact(UUID playerId, double amount) {
        if (amount <= 0) return CompletableFuture.completedFuture(true);
        // Recording a refund is permitted while closing: accepted payments still need settlement.
        return journal.record(playerId, amount).thenCompose(ticket -> {
            if (adapter == null) return CompletableFuture.completedFuture(false);
            return adapter.deposit(pluginName, playerId, amount).thenCompose(success -> success
                ? journal.complete(ticket).thenApply(ignored -> true) : CompletableFuture.completedFuture(false));
        }).whenComplete((success, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(success)) logger.error("Warp refund for {} amount {} remains unresolved; inspect warps/refunds.properties", playerId, amount, failure);
        });
    }

    @Override public double getWarpCost(Player player) { return cost(player, config.get().warpCost(), Permissions.WARP_BYPASS_COST_WARP); }
    @Override public double getSetWarpCost(Player player) { return cost(player, config.get().setWarpCost(), Permissions.WARP_BYPASS_COST_SETWARP); }
    private double cost(Player player, double amount, String bypass) {
        return !config.get().economyEnabled() || player.hasPermission(Permissions.WARP_ADMIN)
            || player.hasPermission(Permissions.WARP_BYPASS_COST) || player.hasPermission(bypass) ? 0.0 : amount;
    }
    @Override public String format(double amount) { return String.format(Locale.ROOT, "$%.2f", amount); }
    @Override public CompletableFuture<Boolean> validateWarpFundsAsync(Player player) { return validateExact(player.getUniqueId(), getWarpCost(player)); }
    @Override public CompletableFuture<Boolean> processWarpCostAsync(Player player) {
        return consume(chargeExact(player.getUniqueId(), getWarpCost(player)));
    }
    @Override public CompletableFuture<Boolean> chargeSuccessAsync(Player player) { return processWarpCostAsync(player); }
    @Override public CompletableFuture<Boolean> processSetWarpCostAsync(Player player) {
        return consume(chargeExact(player.getUniqueId(), getSetWarpCost(player)));
    }
    private CompletableFuture<Boolean> consume(CompletableFuture<Payment> charge) {
        return charge.thenCompose(payment -> settle(payment, false).thenApply(done -> payment.successful() && done));
    }
    @Override public void processRefund(OfflinePlayer player, double amount) { refundExact(player.getUniqueId(), amount); }

    @Override public CompletableFuture<Void> close() {
        closed.set(true);
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
            .orTimeout(config.get().operationTimeoutSeconds(), TimeUnit.SECONDS).handle((value, failure) -> {
                if (adapter != null) adapter.close();
                return null;
            })
            .thenCompose(ignored -> journal.close()).whenComplete((value, failure) -> pending.clear());
    }
    @Override public void shutdown() { close().exceptionally(failure -> { logger.error("Warp economy close failed", failure); return null; }); }
}
