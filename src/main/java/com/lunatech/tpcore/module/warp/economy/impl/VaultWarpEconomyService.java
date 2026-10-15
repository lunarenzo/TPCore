package com.lunatech.tpcore.module.warp.economy.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.WarpTransferTracker;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics;
import com.lunatech.tpcore.module.warp.model.WarpPaymentEvidence;
import com.lunatech.tpcore.module.warp.repository.impl.WarpRefundJournal;
import java.math.BigDecimal;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

public final class VaultWarpEconomyService implements WarpEconomyService {
    private final Supplier<WarpConfig> config;
    private final Logger logger;
    private final String pluginName;
    private volatile VaultUnlockedWarpAdapter adapter;
    private final WarpRefundJournal journal;
    private final Semaphore admission;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final WarpTransferTracker bankTransfers;
    private final Map<UUID, ReceiptState> receipts = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(), circuitOpen = new AtomicBoolean();
    private final CompletableFuture<Void> journalInitialized;
    private static final class Operation {
        volatile UUID ticket;
        volatile CompletableFuture<?> observation;
        final AtomicBoolean expired = new AtomicBoolean();
        final AtomicInteger dispatch = new AtomicInteger();
    }
    private static final class ReceiptState {
        final VaultUnlockedWarpAdapter provider;
        volatile Payment payment;
        final AtomicReference<CompletableFuture<Boolean>> settlement = new AtomicReference<>();
        ReceiptState(VaultUnlockedWarpAdapter provider) { this.provider = provider; }
    }

    public VaultWarpEconomyService(Plugin plugin, Supplier<WarpConfig> config, Logger logger) {
        this(plugin, config, logger, new WarpTransferTracker(config.get().maxPlayerStateEntries()));
    }
    public VaultWarpEconomyService(Plugin plugin, Supplier<WarpConfig> config, Logger logger, WarpTransferTracker transfers) {
        this(plugin, config, logger, VaultUnlockedWarpAdapter.discover(plugin.getClass().getClassLoader()).orElse(null), transfers);
    }
    VaultWarpEconomyService(Plugin plugin, Supplier<WarpConfig> config, Logger logger, VaultUnlockedWarpAdapter adapter, WarpTransferTracker transfers) {
        this.config = config; this.logger = logger; pluginName = plugin.getName(); this.adapter = adapter;
        bankTransfers = transfers;
        WarpConfig settings = config.get();
        admission = new Semaphore(settings.maxConcurrentOperations());
        journal = new WarpRefundJournal(plugin.getDataFolder().toPath().resolve("warps/refunds.properties"),
            settings.maxPlayerStateEntries(), settings.storageQueueCapacity(), settings.payments().resolutionHistoryLimit());
        journalInitialized = journal.ready();
        journalInitialized.whenComplete((value, failure) -> {
            if (failure != null) logger.error("Warp payment journal initialization failed", failure);
            else if (journal.unresolved() > 0) logger.error("Warp payments require reconciliation: {} retained records", journal.unresolved());
        });
    }
    /** Called on the global context after provider registration/plugin lifecycle changes. */
    public void refreshProvider(ClassLoader loader) { replaceProvider(VaultUnlockedWarpAdapter.discover(loader).orElse(null)); }
    void replaceProvider(VaultUnlockedWarpAdapter next) {
        if (closed.get()) { if (next != null) next.close(); return; }
        VaultUnlockedWarpAdapter previous = adapter;
        if (previous != null && previous.sameProvider(next) && previous.active()) return;
        if (previous != null) previous.close();
        adapter = next;
        if (bankTransfers.isEmpty() && journal.unresolved() == 0) circuitOpen.set(false);
    }
    @Override public boolean isAvailable() {
        VaultUnlockedWarpAdapter current = adapter;
        return current != null && current.active() && !closed.get() && journal.isReady() && !circuitOpen.get() && journal.unresolved() == 0;
    }
    @Override public WarpDiagnostics.Economy diagnostics() {
        return new WarpDiagnostics.Economy(isAvailable(), journal.isReady(), circuitOpen.get() || journal.unresolved() > 0,
            pending.size(), journal.isReady() ? journal.pending() : -1, journal.capacity());
    }
    @Override public CompletableFuture<WarpDiagnostics.PaymentPage> paymentIntents(int page, int size) { return journal.snapshot(page, size); }
    @Override public CompletableFuture<Void> acknowledge(String ticket, UUID actor, String note) {
        return journal.acknowledge(ticket, actor, note, () -> {
            try { UUID id = UUID.fromString(ticket); return bankTransfers.contains(id) || receipts.containsKey(id); }
            catch (IllegalArgumentException legacy) { return false; }
        }).thenRun(() -> {
            if (bankTransfers.isEmpty() && journal.unresolved() == 0) circuitOpen.set(false);
            logger.info("Warp payment {} acknowledged by {} with evidence note: {}", ticket, actor, note);
        });
    }
    @Override public CompletableFuture<Void> resume() {
        if (!journal.isReady() || !pending.isEmpty() || !bankTransfers.isEmpty() || journal.unresolved() != 0)
            return CompletableFuture.failedFuture(new IllegalStateException("UNRESOLVED"));
        circuitOpen.set(false);
        return CompletableFuture.completedFuture(null);
    }
    private static boolean timeout(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof TimeoutException;
    }
    private void unknown(UUID ticket, String reason) {
        circuitOpen.set(true);
        if (ticket != null) journal.amend(ticket, evidence -> evidence.observed("UNKNOWN", null, null, reason))
            .exceptionally(failure -> { logger.error("Cannot persist uncertain warp payment {}", ticket, failure); return null; });
    }
    private <T> CompletableFuture<T> bounded(Operation operation, CompletableFuture<T> work) {
        CompletableFuture<T> view = work.copy().orTimeout(config.get().payments().timeoutSeconds(), TimeUnit.SECONDS);
        pending.add(view);
        view.whenComplete((value, failure) -> {
            pending.remove(view);
            operation.observation = null;
            if (timeout(failure)) {
                operation.expired.set(true);
                operation.dispatch.set(2);
                unknown(operation.ticket, "Provider/payment deadline expired; inspect bank history; do not retry");
                if (operation.ticket != null) receipts.remove(operation.ticket);
            }
        });
        return view;
    }
    private <T> CompletableFuture<T> admitted(Function<Operation, CompletableFuture<T>> action) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Warp economy is closed"));
        if (!admission.tryAcquire()) return CompletableFuture.failedFuture(new RejectedExecutionException("Warp economy is busy"));
        Operation operation = new Operation();
        try { return bounded(operation, action.apply(operation)).whenComplete((value, failure) -> admission.release()); }
        catch (RuntimeException failure) { admission.release(); return CompletableFuture.failedFuture(failure); }
    }
    private void live(Operation operation, VaultUnlockedWarpAdapter provider) {
        if (operation.expired.get() || closed.get() || !provider.active()) throw new IllegalStateException("Payment context is no longer active");
    }
    private static <T> CompletableFuture<T> observeRead(Operation operation, CompletableFuture<T> source) {
        CompletableFuture<T> result = new CompletableFuture<>(); operation.observation = result;
        WeakReference<CompletableFuture<T>> observer = new WeakReference<>(result);
        source.whenComplete((value, failure) -> {
            CompletableFuture<T> target = observer.get();
            if (target != null) { if (failure == null) target.complete(value); else target.completeExceptionally(failure); }
        });
        return result;
    }
    private CompletableFuture<VaultUnlockedWarpAdapter.Receipt> transfer(Operation operation, ReceiptState state, UUID player, BigDecimal amount, boolean refund) {
        UUID ticket = operation.ticket;
        return journal.amend(ticket, evidence -> evidence.observed(refund ? "REFUND_PENDING" : "DEBIT_PENDING", null, null, ""))
            .thenCompose(ignored -> {
                live(operation, state.provider);
                if (!bankTransfers.add(ticket)) return CompletableFuture.failedFuture(new RejectedExecutionException("Payment transfer quarantine is full"));
                if (!operation.dispatch.compareAndSet(0, 1)) {
                    bankTransfers.remove(ticket);
                    return CompletableFuture.failedFuture(new IllegalStateException("Payment dispatch deadline expired"));
                }
                CompletableFuture<VaultUnlockedWarpAdapter.Receipt> raw = refund ? state.provider.depositReceipt(pluginName, player, amount)
                    : state.provider.withdrawReceipt(pluginName, player, amount);
                CompletableFuture<VaultUnlockedWarpAdapter.Receipt> result = new CompletableFuture<>(); operation.observation = result;
                WeakReference<CompletableFuture<VaultUnlockedWarpAdapter.Receipt>> observer = new WeakReference<>(result);
                WeakReference<VaultWarpEconomyService> owner = new WeakReference<>(this);
                WarpTransferTracker transfers = bankTransfers;
                raw.whenComplete((receipt, failure) -> recordObservation(owner, observer, operation, refund, transfers, receipt, failure));
                return result;
            });
    }
    private static void recordObservation(WeakReference<VaultWarpEconomyService> owner,
        WeakReference<CompletableFuture<VaultUnlockedWarpAdapter.Receipt>> observer, Operation operation, boolean refund,
        WarpTransferTracker transfers, VaultUnlockedWarpAdapter.Receipt receipt, Throwable failure) {
        VaultWarpEconomyService service = owner.get();
        if (service == null || service.closed.get()) { transfers.remove(operation.ticket); return; }
        String detail = failure == null ? receipt.error() : failure.toString();
        service.journal.amend(operation.ticket, evidence -> evidence.observed(failure != null || operation.expired.get() ? "UNKNOWN" : refund ? "REFUND_RECORDED" : "DEBIT_RECORDED",
            refund || receipt == null ? null : receipt.amount(), refund && receipt != null ? receipt.amount() : null, detail))
            .whenComplete((done, persistenceFailure) -> {
                operation.dispatch.compareAndSet(1, 0);
                service.bankTransfers.remove(operation.ticket);
                CompletableFuture<VaultUnlockedWarpAdapter.Receipt> target = observer.get();
                if (target != null) {
                    Throwable problem = persistenceFailure == null ? failure : persistenceFailure;
                    if (problem == null) target.complete(receipt); else target.completeExceptionally(problem);
                }
            });
    }
    @Override public CompletableFuture<Payment> chargeExact(UUID player, double amount) { return chargeExact(player, amount, "LEGACY_CHARGE", ""); }
    @Override public CompletableFuture<Payment> chargeExact(UUID player, double amount, String purpose, String warp) {
        if (amount == 0) return CompletableFuture.completedFuture(new Payment(player, 0, true));
        if (journalInitialized.isDone() && !isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("Paid warps unavailable; inspect payment diagnostics"));
        VaultUnlockedWarpAdapter provider = adapter;
        if (provider == null) return CompletableFuture.failedFuture(new IllegalStateException("Native async economy provider required"));
        var money = config.get().payments();
        BigDecimal price;
        try { price = money.amount(amount); } catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
        return admitted(operation -> journalInitialized.thenCompose(ignored -> {
            live(operation, provider);
            if (circuitOpen.get() || journal.unresolved() > 0) throw new IllegalStateException("Payment reconciliation required");
            return observeRead(operation, provider.balance(pluginName, player)).thenCompose(balance -> {
                live(operation, provider);
                if (balance.compareTo(price) < 0) return CompletableFuture.completedFuture(new Payment(player, 0, false));
                long now = System.currentTimeMillis();
                WarpPaymentEvidence evidence = new WarpPaymentEvidence(player, purpose, warp, provider.identity(), money.currency(),
                    price, null, null, "PREPARED", now, now, "");
                return journal.record(evidence).thenCompose(ticket -> {
                    operation.ticket = ticket;
                    ReceiptState state = new ReceiptState(provider); receipts.put(ticket, state);
                    if (operation.expired.get()) { receipts.remove(ticket); unknown(ticket, "Deadline expired before transfer dispatch"); return CompletableFuture.failedFuture(new TimeoutException()); }
                    return transfer(operation, state, player, price, false).thenCompose(receipt -> {
                        live(operation, provider);
                        if (!receipt.successful()) {
                            logger.warn("Warp debit rejected: ticket {}, provider {}, reason {}", ticket, provider.identity(), receipt.error());
                            if (receipt.amount().signum() != 0) throw new IllegalStateException("Failed receipt reports a nonzero debit");
                            return journal.complete(ticket).thenCompose(done -> {
                                receipts.remove(ticket);
                                operation.ticket = null; // The zero-debit rejection is settled; a read timeout is not a new transfer.
                                return observeRead(operation, provider.balance(pluginName, player)).thenCompose(currentBalance -> {
                                    live(operation, provider);
                                    if (currentBalance.compareTo(price) < 0) return CompletableFuture.completedFuture(new Payment(player, 0, false));
                                    return CompletableFuture.failedFuture(new IllegalStateException("Provider rejected debit: " + receipt.error()));
                                });
                            });
                        }
                        Payment payment = new Payment(player, receipt.amount(), true, ticket);
                        state.payment = payment;
                        if (receipt.amount().compareTo(price) != 0) return settle(payment, true, operation).thenCompose(done ->
                            CompletableFuture.failedFuture(new IllegalStateException("Receipt differs from price; compensation result=" + done)));
                        return CompletableFuture.completedFuture(payment);
                    }).whenComplete((payment, failure) -> {
                        if (failure != null && receipts.remove(ticket) != null) unknown(ticket, failure.toString());
                    });
                });
            });
        }));
    }
    @Override public CompletableFuture<Boolean> settle(Payment payment, boolean refund) {
        return settle(payment, refund, new Operation());
    }
    private CompletableFuture<Boolean> settle(Payment payment, boolean refund, Operation operation) {
        if (payment == null || !payment.successful()) return CompletableFuture.completedFuture(true);
        if (payment.ticket() == null) return CompletableFuture.completedFuture(payment.exactAmount().signum() == 0);
        ReceiptState state = receipts.get(payment.ticket());
        if (state == null) return CompletableFuture.failedFuture(new IllegalStateException("No live receipt; reconciliation required"));
        if (!payment.equals(state.payment)) return CompletableFuture.failedFuture(new IllegalArgumentException("Settlement must match the captured receipt"));
        CompletableFuture<Boolean> previous = state.settlement.get();
        if (previous != null) return previous;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!state.settlement.compareAndSet(null, result)) return state.settlement.get();
        operation.ticket = payment.ticket();
        CompletableFuture<Boolean> work;
        try {
            if (refund && payment.exactAmount().signum() > 0) {
                work = transfer(operation, state, payment.playerId(), payment.exactAmount(), true).thenCompose(receipt -> {
                    live(operation, state.provider);
                    if (!receipt.successful() || receipt.amount().compareTo(payment.exactAmount()) != 0) {
                        unknown(payment.ticket(), "Refund incomplete: " + receipt.error()); return CompletableFuture.completedFuture(false);
                    }
                    return journal.complete(payment.ticket()).thenApply(done -> true);
                });
            } else work = journal.complete(payment.ticket()).thenApply(done -> true);
        } catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        bounded(operation, work).whenComplete((success, failure) -> {
            receipts.remove(payment.ticket(), state);
            if (failure != null) { unknown(payment.ticket(), failure.toString()); result.completeExceptionally(failure); }
            else result.complete(success);
        });
        return result;
    }
    @Override public CompletableFuture<Boolean> validateExact(UUID player, double amount) {
        if (amount == 0) return CompletableFuture.completedFuture(true);
        VaultUnlockedWarpAdapter provider = adapter;
        if (provider == null || !isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("Native async economy unavailable"));
        BigDecimal price;
        try { price = config.get().payments().amount(amount); } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        return admitted(operation -> observeRead(operation, provider.balance(pluginName, player)).thenApply(balance -> balance.compareTo(price) >= 0));
    }
    @Override public CompletableFuture<Boolean> refundExact(UUID player, double amount) {
        if (amount == 0) return CompletableFuture.completedFuture(true);
        VaultUnlockedWarpAdapter provider = adapter;
        if (provider == null || !isAvailable()) return CompletableFuture.failedFuture(new IllegalStateException("Economy unavailable"));
        BigDecimal value = config.get().payments().amount(amount);
        return admitted(operation -> {
            long now = System.currentTimeMillis();
            return journal.record(new WarpPaymentEvidence(player, "LEGACY_REFUND", "", provider.identity(), config.get().payments().currency(),
                value, value, null, "PREPARED", now, now, "Refund requested through legacy API"))
                .thenCompose(ticket -> {
                    operation.ticket = ticket;
                    if (operation.expired.get() || closed.get() || !provider.active()) {
                        unknown(ticket, "Legacy refund deadline/context expired before dispatch"); return CompletableFuture.failedFuture(new IllegalStateException("Refund context expired"));
                    }
                    ReceiptState state = new ReceiptState(provider); state.payment = new Payment(player, value, true, ticket);
                    receipts.put(ticket, state); return settle(state.payment, true, operation);
                });
        });
    }
    @Override public double getWarpCost(Player player) { return cost(player, config.get().warpCost(), Permissions.WARP_BYPASS_COST_WARP); }
    @Override public double getSetWarpCost(Player player) { return cost(player, config.get().setWarpCost(), Permissions.WARP_BYPASS_COST_SETWARP); }
    private double cost(Player player, double amount, String bypass) {
        return !config.get().economyEnabled() || player.hasPermission(Permissions.WARP_ADMIN)
            || player.hasPermission(Permissions.WARP_BYPASS_COST) || player.hasPermission(bypass) ? 0 : amount;
    }
    @Override public String format(double amount) { return config.get().payments().display(config.get().payments().amount(amount)); }
    @Override public String format(BigDecimal amount) { return config.get().payments().display(amount); }
    @Override public CompletableFuture<Boolean> validateWarpFundsAsync(Player player) { return validateExact(player.getUniqueId(), getWarpCost(player)); }
    @Override public CompletableFuture<Boolean> processWarpCostAsync(Player player) { return consume(chargeExact(player.getUniqueId(), getWarpCost(player))); }
    @Override public CompletableFuture<Boolean> chargeSuccessAsync(Player player) { return processWarpCostAsync(player); }
    @Override public CompletableFuture<Boolean> processSetWarpCostAsync(Player player) { return consume(chargeExact(player.getUniqueId(), getSetWarpCost(player))); }
    private CompletableFuture<Boolean> consume(CompletableFuture<Payment> charge) { return charge.thenCompose(payment -> settle(payment, false).thenApply(done -> payment.successful() && done)); }
    @Override public void processRefund(OfflinePlayer player, double amount) { refundExact(player.getUniqueId(), amount).exceptionally(failure -> { logger.error("Legacy warp refund failed", failure); return false; }); }
    @Override public CompletableFuture<Void> close() {
        closed.set(true);
        VaultUnlockedWarpAdapter current = adapter; if (current != null) current.close();
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
            .orTimeout(config.get().payments().timeoutSeconds(), TimeUnit.SECONDS).handle((value, failure) -> null)
            .thenCompose(ignored -> journal.close()).whenComplete((value, failure) -> { pending.clear(); receipts.clear(); adapter = null; });
    }
    @Override public void shutdown() { close().exceptionally(failure -> { logger.error("Warp economy close failed", failure); return null; }); }
}
