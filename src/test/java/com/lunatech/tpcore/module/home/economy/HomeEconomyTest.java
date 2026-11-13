package com.lunatech.tpcore.module.home.economy;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.config.model.HomeRuntimeConfig;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.model.HomePayment;
import com.lunatech.tpcore.module.home.model.HomePayment.Action;
import com.lunatech.tpcore.module.home.model.HomePayment.State;
import com.lunatech.tpcore.module.home.repository.impl.SqliteHomeRepository;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HomeEconomyTest {
    private static final BigDecimal COST = new BigDecimal("12.34");
    @TempDir Path directory;

    @Test
    void cancelledTravelRefundsLateDebitExactlyOnceAndBlocksDuplicateWhileActive() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge(Action.TRAVEL);
            assertTrue(fixture.bank.started.await(5, TimeUnit.SECONDS));
            assertEquals(State.DEBIT_PENDING, fixture.record(attempt.id()).state());
            assertEquals(HomeResultStatus.PAYMENT_PENDING, fixture.charge(Action.CREATE).ready().join());
            assertFalse(fixture.economy.acknowledge(attempt.id(), "operator", 512).join());
            attempt.abort();
            fixture.bank.withdrawal.complete(new VaultHomeAdapter.Receipt(true, COST));
            assertEquals(COST, fixture.refunded.get(5, TimeUnit.SECONDS));
            assertEquals(State.REFUNDED, fixture.record(attempt.id()).state());
            assertEquals(1, fixture.bank.withdrawals.get());
            assertEquals(1, fixture.bank.refunds.get());
        }
    }

    @Test
    void lateTravelResultSettlesOnceAndProviderReplacementRetainsRefundEvidence() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge(Action.TRAVEL);
            fixture.bank.withdrawal.complete(new VaultHomeAdapter.Receipt(true, COST));
            assertEquals(HomeResultStatus.SUCCESS, attempt.ready().get(5, TimeUnit.SECONDS));
            assertTrue(attempt.claimEffect());
            attempt.abort();
            assertEquals(State.TELEPORT_PENDING, fixture.record(attempt.id()).state());
            fixture.bank.current = false;
            attempt.travelOutcome(false, null);
            attempt.travelOutcome(false, null);
            assertEquals(COST, fixture.await(attempt.id(), State.REFUND_UNKNOWN).debit());
            assertEquals(0, fixture.bank.refunds.get());
        }
    }

    @Test
    void paidCreationCommitsTheHomeAndSettlementTogether() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge(Action.CREATE);
            fixture.bank.withdrawal.complete(new VaultHomeAdapter.Receipt(true, COST));
            assertEquals(HomeResultStatus.SUCCESS, attempt.ready().get(5, TimeUnit.SECONDS));
            assertTrue(attempt.claimEffect());
            var home = new Home(fixture.player, "base", "world", UUID.randomUUID(), 1, 64, 1, 0, 0, 1);
            var saved = fixture.repository.saveChecked(home, null, 1, attempt.id(), 512).join();
            attempt.creationOutcome(saved, null);
            assertEquals(HomeResultStatus.SUCCESS, saved);
            assertEquals(State.KEPT, fixture.record(attempt.id()).state());
            assertEquals(home, fixture.repository.findByName(fixture.player, "base").join().orElseThrow());
            assertEquals(0, fixture.bank.refunds.get());
        }
    }

    @Test
    void insufficientFundsLeavesARejectedRecordWithoutWithdrawing() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.bank.balance = BigDecimal.ONE;
            var attempt = fixture.charge(Action.TRAVEL);
            assertEquals(HomeResultStatus.INSUFFICIENT_FUNDS, attempt.ready().get(5, TimeUnit.SECONDS));
            assertEquals(State.REJECTED, fixture.record(attempt.id()).state());
            assertEquals(0, fixture.bank.withdrawals.get());
        }
    }

    @Test
    void paidCreationRefundsIfTheLastSlotWasTakenDuringTheDebit() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge(Action.CREATE);
            var world = UUID.randomUUID();
            var other = new Home(fixture.player, "other", "world", world, 2, 64, 2, 0, 0, 1);
            assertEquals(HomeResultStatus.SUCCESS, fixture.repository.save(other, false, 1).join());
            fixture.bank.withdrawal.complete(new VaultHomeAdapter.Receipt(true, COST));
            assertEquals(HomeResultStatus.SUCCESS, attempt.ready().get(5, TimeUnit.SECONDS));
            assertTrue(attempt.claimEffect());
            var home = new Home(fixture.player, "base", "world", world, 1, 64, 1, 0, 0, 2);
            var saved = fixture.repository.saveChecked(home, null, 1, attempt.id(), 512).join();
            attempt.creationOutcome(saved, null);
            assertEquals(HomeResultStatus.LIMIT_REACHED, saved);
            assertEquals(COST, fixture.refunded.get(5, TimeUnit.SECONDS));
            assertTrue(fixture.repository.findByName(fixture.player, "base").join().isEmpty());
        }
    }

    @Test
    void balanceTimeoutClosesAdmissionWithoutStartingAWithdrawal() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.shortTimeout();
            fixture.bank.balanceReply = new CompletableFuture<>();
            var attempt = fixture.charge(Action.TRAVEL);
            assertEquals(HomeResultStatus.ECONOMY_UNAVAILABLE, attempt.ready().get(4, TimeUnit.SECONDS));
            assertEquals(State.REJECTED, fixture.await(attempt.id(), State.REJECTED).state());
            assertEquals(0, fixture.bank.withdrawals.get());
            assertTrue(fixture.economy.circuitStatus().contains("active=0"));
            assertTrue(fixture.economy.isolateProvider());
            assertFalse(fixture.economy.resetCircuit());
            fixture.bank.balanceReply.complete(BigDecimal.valueOf(100));
        }
    }

    @Test
    void stalledDebitCanCompleteAndRefundAfterPlayerDeadlineWithoutReplayingWithdrawal() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.shortTimeout();
            var attempt = fixture.charge(Action.TRAVEL);
            assertEquals(HomeResultStatus.PAYMENT_PENDING, attempt.ready().get(4, TimeUnit.SECONDS));
            assertTrue(fixture.economy.circuitStatus().contains("active=0"));
            assertFalse(fixture.economy.acknowledge(attempt.id(), "operator", 512).join());
            assertTrue(fixture.economy.isolateProvider());
            fixture.bank.withdrawal.complete(new VaultHomeAdapter.Receipt(true, COST));
            assertEquals(COST, fixture.refunded.get(5, TimeUnit.SECONDS));
            assertEquals(State.REFUNDED, fixture.record(attempt.id()).state());
            assertEquals(1, fixture.bank.withdrawals.get());
            assertEquals(1, fixture.bank.refunds.get());
        }
    }

    @Test
    void saturatedGameplayQueueStillRecordsProviderReceiptWithoutAnotherBankCall() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge(Action.CREATE);
            assertTrue(fixture.bank.started.await(5, TimeUnit.SECONDS));
            try (var lock = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("tpcore.db"));
                 var statement = lock.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                var blocker = fixture.repository.archive(fixture.player, "absent", 10, 1000);
                var reads = new ArrayList<CompletableFuture<?>>();
                for (int index = 0; index < 32; index++) reads.add(fixture.repository.list(fixture.player, 1, 0));
                assertTrue(reads.stream().anyMatch(CompletableFuture::isCompletedExceptionally));
                fixture.bank.withdrawal.complete(new VaultHomeAdapter.Receipt(true, COST));
                statement.execute("COMMIT");
                blocker.get(5, TimeUnit.SECONDS);
                assertEquals(HomeResultStatus.SUCCESS, attempt.ready().get(5, TimeUnit.SECONDS));
                assertEquals(COST, fixture.record(attempt.id()).debit());
                attempt.abort();
                fixture.refunded.get(5, TimeUnit.SECONDS);
                assertEquals(1, fixture.bank.withdrawals.get());
                assertEquals(1, fixture.bank.refunds.get());
            }
        }
    }

    @Test
    void settlementDrainWaitsForLateDebitAndItsExactRefund() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge(Action.CREATE);
            assertTrue(fixture.bank.started.await(5, TimeUnit.SECONDS));
            var drain = fixture.economy.close(3);
            fixture.repository.settlementDrain(drain);
            var storageClosed = fixture.repository.close();
            assertFalse(storageClosed.isDone());
            fixture.bank.withdrawal.complete(new VaultHomeAdapter.Receipt(true, COST));
            assertEquals(COST, fixture.refunded.get(5, TimeUnit.SECONDS));
            storageClosed.get(5, TimeUnit.SECONDS);
        }
        var reopened = new SqliteHomeRepository(directory.toFile(), LoggerFactory.getLogger(getClass()), 8, 3);
        try {
            reopened.initialize().join();
            assertEquals(State.REFUNDED, reopened.listPayments(0).join().getFirst().state());
        } finally { reopened.close().join(); }
    }

    @Test
    void restartRetainsUncertainDebitAndAcknowledgmentDoesNotTransfer() throws Exception {
        UUID player = UUID.randomUUID();
        UUID id;
        try (var before = new Fixture(player)) {
            var attempt = before.charge(Action.CREATE);
            id = attempt.id();
            assertTrue(before.bank.started.await(5, TimeUnit.SECONDS));
        }
        try (var after = new Fixture(player)) {
            assertEquals(State.DEBIT_PENDING, after.record(id).state());
            assertEquals(HomeResultStatus.PAYMENT_PENDING, after.charge(Action.TRAVEL).ready().get(5, TimeUnit.SECONDS));
            assertTrue(after.economy.acknowledge(id, "operator", 512).join());
            assertEquals("operator", after.record(id).operator());
            assertEquals(State.ACKNOWLEDGED, after.record(id).state());
            assertEquals(0, after.bank.withdrawals.get());
            assertEquals(0, after.bank.refunds.get());
        }
    }

    private final class Fixture implements AutoCloseable {
        final UUID player;
        final SqliteHomeRepository repository;
        final HomeEconomy.BankLane lane = new HomeEconomy.BankLane();
        final Bank bank = new Bank();
        final HomeEconomy economy;
        final CompletableFuture<BigDecimal> refunded = new CompletableFuture<>();
        HomeConfig config = HomeConfig.createDefault();

        Fixture() { this(UUID.randomUUID()); }

        Fixture(UUID player) {
            this.player = player;
            var logger = LoggerFactory.getLogger(HomeEconomyTest.class);
            repository = new SqliteHomeRepository(directory.toFile(), logger, 16, 3);
            repository.initialize().join();
            Plugin plugin = mock(Plugin.class);
            when(plugin.getName()).thenReturn("TPCore");
            when(plugin.getSLF4JLogger()).thenReturn(logger);
            economy = new HomeEconomy(plugin, repository, lane, () -> bank);
        }

        HomeEconomy.Attempt charge(Action action) {
            return economy.charge(player, "base", action, COST, config, refunded::complete);
        }

        void shortTimeout() {
            config = spy(config);
            HomeRuntimeConfig runtime = spy(config.runtime());
            doReturn(1).when(runtime).timeoutSeconds();
            doReturn(runtime).when(config).runtime();
        }

        HomePayment record(UUID id) { return repository.findPayment(id).join().orElseThrow(); }

        HomePayment await(UUID id, State state) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            HomePayment payment;
            do {
                payment = record(id);
                if (payment.state() == state) return payment;
                Thread.sleep(10);
            } while (System.nanoTime() - deadline < 0);
            assertEquals(state, payment.state());
            return payment;
        }

        @Override public void close() {
            economy.close(1);
            lane.close();
            repository.close().join();
        }
    }

    private static final class Bank implements HomeEconomy.Bank {
        final CountDownLatch started = new CountDownLatch(1);
        final CompletableFuture<VaultHomeAdapter.Receipt> withdrawal = new CompletableFuture<>();
        final AtomicInteger withdrawals = new AtomicInteger();
        final AtomicInteger refunds = new AtomicInteger();
        volatile boolean current = true;
        volatile BigDecimal balance = BigDecimal.valueOf(100);
        CompletableFuture<BigDecimal> balanceReply;

        @Override public String identity() { return "test-bank"; }
        @Override public boolean current() { return current; }
        @Override public CompletableFuture<BigDecimal> balance(String plugin, UUID player) {
            return balanceReply == null ? CompletableFuture.completedFuture(balance) : balanceReply;
        }

        @Override public CompletableFuture<VaultHomeAdapter.Receipt> transfer(boolean refund, String plugin, UUID player, BigDecimal amount) {
            if (refund) {
                refunds.incrementAndGet();
                return CompletableFuture.completedFuture(new VaultHomeAdapter.Receipt(true, amount));
            }
            withdrawals.incrementAndGet();
            started.countDown();
            return withdrawal;
        }
    }
}
