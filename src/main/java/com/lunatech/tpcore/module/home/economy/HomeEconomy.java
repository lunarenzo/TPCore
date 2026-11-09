package com.lunatech.tpcore.module.home.economy;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.config.model.HomeEconomyConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.model.HomePayment;
import com.lunatech.tpcore.module.home.model.HomePayment.Action;
import com.lunatech.tpcore.module.home.model.HomePayment.State;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class HomeEconomy {
    interface Bank {
        String identity();
        boolean current();
        CompletableFuture<BigDecimal> balance(String plugin, UUID player);
        CompletableFuture<VaultHomeAdapter.Receipt> transfer(boolean refund, String plugin, UUID player, BigDecimal amount);
    }

    public static final class BankLane implements AutoCloseable {
        private final Map<UUID, Attempt> active = new HashMap<>();
        private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofVirtual().name("tpcore-home-bank-", 0).factory());
        public BankLane() { io.allowCoreThreadTimeOut(true); }
        @Override public void close() { io.shutdown(); }
    }

    private enum Decision { OPEN, ABORTED, EFFECT }
    private final Plugin plugin;
    private final HomeRepository repository;
    private final Executor executor;
    private final Supplier<Bank> provider;
    private final Map<UUID, Attempt> active;
    private volatile boolean closed;
    private VaultHomeAdapter adapter;

    public HomeEconomy(Plugin plugin, HomeRepository repository, BankLane lane) {
        this(plugin, repository, lane, null);
    }

    HomeEconomy(Plugin plugin, HomeRepository repository, BankLane lane, Supplier<Bank> provider) {
        this.plugin = plugin;
        this.repository = repository;
        executor = lane.io;
        active = lane.active;
        this.provider = provider == null ? this::discover : provider;
    }

    private synchronized Bank discover() {
        try {
            if (adapter == null) adapter = new VaultHomeAdapter(plugin.getClass().getClassLoader());
            return adapter.provider();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    public static BigDecimal fee(Player player, HomeConfig config, boolean creation) {
        var pricing = config.economy();
        if (pricing == null) return BigDecimal.ZERO;
        BigDecimal cost = pricing.cost(creation);
        if (cost.signum() == 0 || player.hasPermission(Permissions.HOME_BYPASS_COST)
            || player.hasPermission(creation ? Permissions.HOME_BYPASS_CREATE_COST : Permissions.HOME_BYPASS_TELEPORT_COST))
            return BigDecimal.ZERO;
        return cost;
    }

    public Attempt charge(UUID player, String home, Action action, BigDecimal cost, HomeConfig config, Consumer<BigDecimal> refunded) {
        var attempt = new Attempt(player, home, action, cost, config.economy(), refunded);
        synchronized (active) {
            if (active.containsKey(player)) {
                attempt.ready.complete(HomeResultStatus.PAYMENT_PENDING);
                return attempt;
            }
            if (closed || active.size() >= config.runtime().maxRequests()) {
                attempt.ready.complete(HomeResultStatus.ECONOMY_UNAVAILABLE);
                return attempt;
            }
            active.put(player, attempt);
        }
        attempt.start();
        return attempt;
    }

    public CompletableFuture<Boolean> acknowledge(UUID id, String operator, int historyLimit) {
        synchronized (active) {
            if (active.values().stream().anyMatch(attempt -> attempt.id.equals(id))) return CompletableFuture.completedFuture(false);
            return repository.acknowledgePayment(id, operator, historyLimit);
        }
    }

    public void close() {
        Attempt[] pending;
        synchronized (active) {
            closed = true;
            pending = active.values().toArray(Attempt[]::new);
        }
        for (Attempt attempt : pending) {
            if (attempt.economy() == this) attempt.abort();
        }
    }

    private <T> CompletableFuture<T> work(Supplier<T> supplier) {
        try { return CompletableFuture.supplyAsync(supplier, executor); }
        catch (RejectedExecutionException failure) { return CompletableFuture.failedFuture(failure); }
    }

    public final class Attempt {
        private final UUID id = UUID.randomUUID();
        private final UUID player;
        private final String home;
        private final Action action;
        private final BigDecimal cost;
        private final HomeEconomyConfig config;
        private final Consumer<BigDecimal> refunded;
        private final State debitedState;
        private final CompletableFuture<HomeResultStatus> ready = new CompletableFuture<>();
        private final AtomicReference<Decision> decision = new AtomicReference<>(Decision.OPEN);
        private final AtomicBoolean settling = new AtomicBoolean();
        private volatile Bank bank;
        private volatile BigDecimal debit;

        private Attempt(UUID player, String home, Action action, BigDecimal cost, HomeEconomyConfig config, Consumer<BigDecimal> refunded) {
            this.player = player; this.home = home; this.action = action; this.cost = cost; this.config = config; this.refunded = refunded;
            debitedState = action == Action.CREATE ? State.DEBITED : State.TELEPORT_PENDING;
        }

        public UUID id() { return id; }
        private HomeEconomy economy() { return HomeEconomy.this; }
        public CompletableFuture<HomeResultStatus> ready() { return ready; }
        public boolean claimEffect() { return !closed && decision.compareAndSet(Decision.OPEN, Decision.EFFECT); }

        public void abort() {
            if (decision.compareAndSet(Decision.OPEN, Decision.ABORTED))
                ready.thenAccept(status -> { if (status == HomeResultStatus.SUCCESS) refund(); });
        }

        private void start() {
            work(provider).whenComplete((selected, failure) -> {
                if (failure != null || selected == null || closed || decision.get() == Decision.ABORTED) {
                    fail(HomeResultStatus.ECONOMY_UNAVAILABLE);
                    return;
                }
                bank = selected;
                var payment = new HomePayment(id, player, home, action, bank.identity(), cost, null, null,
                    State.DEBIT_PENDING, System.currentTimeMillis(), null);
                repository.beginPayment(payment, config.maxUnresolvedPayments()).whenComplete((status, error) -> {
                    if (error != null) { fail(HomeResultStatus.ECONOMY_UNAVAILABLE); return; }
                    if (status != HomeResultStatus.SUCCESS) { fail(status); return; }
                    checkBalance();
                });
            });
        }

        private void checkBalance() {
            work(() -> closed || decision.get() == Decision.ABORTED || !bank.current()
                ? CompletableFuture.<BigDecimal>completedFuture(null) : bank.balance(plugin.getName(), player))
                .thenCompose(future -> future).whenComplete((balance, failure) -> {
                    if (failure != null || balance == null) reject(HomeResultStatus.ECONOMY_UNAVAILABLE);
                    else if (balance.compareTo(cost) < 0) reject(HomeResultStatus.INSUFFICIENT_FUNDS);
                    else withdraw();
                });
        }

        private void withdraw() {
            work(() -> closed || decision.get() == Decision.ABORTED || !bank.current()
                ? CompletableFuture.<VaultHomeAdapter.Receipt>completedFuture(null) : bank.transfer(false, plugin.getName(), player, cost))
                .thenCompose(future -> future).whenComplete((receipt, failure) -> {
                    if (failure != null) { unresolved(State.DEBIT_PENDING, null, null); return; }
                    if (receipt == null) { reject(HomeResultStatus.ECONOMY_UNAVAILABLE); return; }
                    if (!receipt.successful()) {
                        if (receipt.amount().signum() == 0) reject(HomeResultStatus.PAYMENT_REJECTED);
                        else unresolved(State.DEBIT_PENDING, receipt.amount(), null);
                        return;
                    }
                    debit = receipt.amount();
                    update(State.DEBIT_PENDING, debitedState, debit, null).whenComplete((ok, error) -> {
                        if (!Boolean.TRUE.equals(ok)) { fail(HomeResultStatus.PAYMENT_PENDING); return; }
                        if (debit.compareTo(cost) == 0) ready.complete(HomeResultStatus.SUCCESS);
                        else if (debit.signum() == 0) settle(debitedState, State.REJECTED, HomeResultStatus.PAYMENT_REJECTED);
                        else {
                            refund().whenComplete((confirmed, problem) -> ready.complete(Boolean.TRUE.equals(confirmed)
                                ? HomeResultStatus.PAYMENT_REJECTED : HomeResultStatus.PAYMENT_PENDING));
                        }
                    });
                });
        }

        private void reject(HomeResultStatus status) { settle(State.DEBIT_PENDING, State.REJECTED, status); }

        private void settle(State from, State to, HomeResultStatus status) {
            update(from, to, null, null).whenComplete((ok, failure) -> fail(Boolean.TRUE.equals(ok) ? status : HomeResultStatus.PAYMENT_PENDING));
        }

        private void unresolved(State from, BigDecimal observedDebit, BigDecimal observedRefund) {
            update(from, State.REVIEW, observedDebit, observedRefund).whenComplete((ok, failure) -> fail(HomeResultStatus.PAYMENT_PENDING));
        }

        public void travelOutcome(Boolean arrived, Throwable failure) {
            if (Boolean.FALSE.equals(arrived) && failure == null) {
                refund();
            } else if (settling.compareAndSet(false, true)) {
                if (failure != null || arrived == null) unresolved(debitedState, null, null);
                else settle(debitedState, State.KEPT, HomeResultStatus.SUCCESS);
            }
        }

        public void creationOutcome(HomeResultStatus status, Throwable failure) {
            boolean unknown = failure != null || status == null || status == HomeResultStatus.STORAGE_ERROR
                || status == HomeResultStatus.PAYMENT_PENDING;
            if (!unknown && !status.saved()) refund();
            else if (settling.compareAndSet(false, true)) {
                if (unknown) unresolved(debitedState, null, null);
                else release(); // The insert already settled this payment.
            }
        }

        private CompletableFuture<Boolean> refund() {
            if (!settling.compareAndSet(false, true)) return CompletableFuture.completedFuture(false);
            return update(debitedState, State.REFUND_PENDING, null, null).thenCompose(ok -> {
                if (!ok) return CompletableFuture.completedFuture(false);
                return work(() -> bank.current() ? bank.transfer(true, plugin.getName(), player, debit)
                    : CompletableFuture.<VaultHomeAdapter.Receipt>completedFuture(null))
                    .thenCompose(future -> future).handle((receipt, failure) -> receipt)
                    .thenCompose(receipt -> {
                        boolean exact = receipt != null && receipt.successful() && receipt.amount().compareTo(debit) == 0;
                        return update(State.REFUND_PENDING, exact ? State.REFUNDED : State.REVIEW, null,
                            receipt == null ? null : receipt.amount()).thenApply(saved -> exact && saved);
                    });
            }).whenComplete((ok, failure) -> {
                release();
                if (Boolean.TRUE.equals(ok)) refunded.accept(debit);
                else plugin.getSLF4JLogger().warn("Home payment {} needs refund reconciliation", id);
            });
        }

        private CompletableFuture<Boolean> update(State from, State to, BigDecimal observedDebit, BigDecimal observedRefund) {
            return repository.updatePayment(id, from, to, observedDebit, observedRefund, config.paymentHistoryLimit());
        }

        private void fail(HomeResultStatus status) {
            release();
            if (status == HomeResultStatus.PAYMENT_PENDING) plugin.getSLF4JLogger().warn("Home payment {} needs reconciliation", id);
            ready.complete(status);
        }

        private void release() {
            synchronized (active) { active.remove(player, this); }
        }
    }
}
