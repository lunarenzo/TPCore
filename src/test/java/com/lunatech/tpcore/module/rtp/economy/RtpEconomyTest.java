package com.lunatech.tpcore.module.rtp.economy;

import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpConfig.Runtime;
import com.lunatech.tpcore.module.rtp.model.RtpPayment;
import com.lunatech.tpcore.module.rtp.model.RtpPayment.State;
import com.lunatech.tpcore.module.rtp.repository.impl.SqliteRtpPaymentRepository;
import com.lunatech.tpcore.module.rtp.economy.RtpPaymentStatus;
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

class RtpEconomyTest {
    private static final BigDecimal COST = new BigDecimal("12.34");
    @TempDir Path directory;

    @Test
    void cancelledTravelRefundsLateDebitExactlyOnceAndBlocksDuplicateWhileActive() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge();
            assertTrue(fixture.bank.started.await(5, TimeUnit.SECONDS));
            assertEquals(State.DEBIT_PENDING, fixture.record(attempt.id()).state());
            assertEquals(RtpPaymentStatus.PAYMENT_PENDING, fixture.charge().ready().join());
            assertFalse(fixture.economy.acknowledge(attempt.id(), "operator", 512).join());
            attempt.abort();
            fixture.bank.withdrawal.complete(new VaultRtpAdapter.Receipt(true, COST));
            assertEquals(COST, fixture.refunded.get(5, TimeUnit.SECONDS));
            assertEquals(State.REFUNDED, fixture.record(attempt.id()).state());
            assertEquals(1, fixture.bank.withdrawals.get());
            assertEquals(1, fixture.bank.refunds.get());
        }
    }

    @Test
    void lateTravelResultSettlesOnceAndProviderReplacementRetainsRefundEvidence() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge();
            fixture.bank.withdrawal.complete(new VaultRtpAdapter.Receipt(true, COST));
            assertEquals(RtpPaymentStatus.SUCCESS, attempt.ready().get(5, TimeUnit.SECONDS));
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
    void insufficientFundsLeavesARejectedRecordWithoutWithdrawing() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.bank.balance = BigDecimal.ONE;
            var attempt = fixture.charge();
            assertEquals(RtpPaymentStatus.INSUFFICIENT_FUNDS, attempt.ready().get(5, TimeUnit.SECONDS));
            assertEquals(State.REJECTED, fixture.record(attempt.id()).state());
            assertEquals(0, fixture.bank.withdrawals.get());
        }
    }

    @Test
    void balanceTimeoutClosesAdmissionWithoutStartingAWithdrawal() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.shortTimeout();
            fixture.bank.balanceReply = new CompletableFuture<>();
            var attempt = fixture.charge();
            assertEquals(RtpPaymentStatus.ECONOMY_UNAVAILABLE, attempt.ready().get(4, TimeUnit.SECONDS));
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
            var attempt = fixture.charge();
            assertEquals(RtpPaymentStatus.PAYMENT_PENDING, attempt.ready().get(4, TimeUnit.SECONDS));
            assertTrue(fixture.economy.circuitStatus().contains("active=0"));
            assertFalse(fixture.economy.acknowledge(attempt.id(), "operator", 512).join());
            assertTrue(fixture.economy.isolateProvider());
            fixture.bank.withdrawal.complete(new VaultRtpAdapter.Receipt(true, COST));
            assertEquals(COST, fixture.refunded.get(5, TimeUnit.SECONDS));
            assertEquals(State.REFUNDED, fixture.record(attempt.id()).state());
            assertEquals(1, fixture.bank.withdrawals.get());
            assertEquals(1, fixture.bank.refunds.get());
        }
    }

    @Test
    void settlementDrainWaitsForLateDebitAndItsExactRefund() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge();
            assertTrue(fixture.bank.started.await(5, TimeUnit.SECONDS));
            var drain = fixture.economy.close(3);
            fixture.repository.settlementDrain(drain);
            var storageClosed = fixture.repository.close();
            assertFalse(storageClosed.isDone());
            fixture.bank.withdrawal.complete(new VaultRtpAdapter.Receipt(true, COST));
            assertEquals(COST, fixture.refunded.get(5, TimeUnit.SECONDS));
            storageClosed.get(5, TimeUnit.SECONDS);
        }
        var reopened = new SqliteRtpPaymentRepository(directory.resolve("payments.db"), LoggerFactory.getLogger(getClass()), 10);
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
            var attempt = before.charge();
            id = attempt.id();
            assertTrue(before.bank.started.await(5, TimeUnit.SECONDS));
        }
        try (var after = new Fixture(player)) {
            assertEquals(State.DEBIT_PENDING, after.record(id).state());
            assertEquals(RtpPaymentStatus.PAYMENT_PENDING, after.charge().ready().get(5, TimeUnit.SECONDS));
            assertTrue(after.economy.acknowledge(id, "operator", 512).join());
            assertEquals("operator", after.record(id).operator());
            assertEquals(State.ACKNOWLEDGED, after.record(id).state());
            assertEquals(0, after.bank.withdrawals.get());
            assertEquals(0, after.bank.refunds.get());
        }
    }

    @Test void partialRefundKeepsItsObservedCreditAndIsNeverRetriedAutomatically() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.bank.refundAmount = BigDecimal.ONE;
            var attempt = fixture.charge();
            fixture.bank.withdrawal.complete(new VaultRtpAdapter.Receipt(true, COST));
            assertEquals(RtpPaymentStatus.SUCCESS, attempt.ready().get(5, TimeUnit.SECONDS));
            attempt.abort();
            var record = fixture.await(attempt.id(), State.REFUND_UNKNOWN);
            assertEquals(COST, record.debit()); assertEquals(BigDecimal.ONE, record.refund());
            assertTrue(fixture.economy.acknowledge(attempt.id(), "operator", 512).join());
            assertEquals(1, fixture.bank.refunds.get()); assertEquals(1, fixture.bank.withdrawals.get());
        }
    }
    @Test void withdrawalRejectionIsNotMisclassifiedAsInsufficientFundsOrReplayed() throws Exception {
        try (var fixture = new Fixture()) {
            var attempt = fixture.charge();
            fixture.bank.withdrawal.complete(new VaultRtpAdapter.Receipt(false, BigDecimal.ZERO));
            assertEquals(RtpPaymentStatus.PAYMENT_REJECTED, attempt.ready().get(5, TimeUnit.SECONDS));
            assertEquals(State.REJECTED, fixture.record(attempt.id()).state());
            assertEquals(1, fixture.bank.withdrawals.get()); assertEquals(0, fixture.bank.refunds.get());
        }
    }

    private final class Fixture implements AutoCloseable {
        final UUID player;
        final SqliteRtpPaymentRepository repository;
        final RtpEconomy.BankLane lane = new RtpEconomy.BankLane();
        final Bank bank = new Bank();
        final RtpEconomy economy;
        final CompletableFuture<BigDecimal> refunded = new CompletableFuture<>();
        RtpConfig config = RtpConfig.createDefault();

        Fixture() { this(UUID.randomUUID()); }

        Fixture(UUID player) {
            this.player = player;
            var logger = LoggerFactory.getLogger(RtpEconomyTest.class);
            repository = new SqliteRtpPaymentRepository(directory.resolve("payments.db"), logger, 10);
            repository.initialize().join();
            Plugin plugin = mock(Plugin.class);
            when(plugin.getName()).thenReturn("TPCore");
            when(plugin.getSLF4JLogger()).thenReturn(logger);
            economy = new RtpEconomy(plugin, repository, lane, () -> bank);
        }

        RtpEconomy.Attempt charge() {
            return economy.charge(player, "world", COST, config, refunded::complete);
        }

        void shortTimeout() {
            config = spy(config);
            RtpConfig.Runtime runtime = spy(config.runtime());
            doReturn(1).when(runtime).timeoutSeconds();
            doReturn(runtime).when(config).runtime();
        }

        RtpPayment record(UUID id) { return repository.findPayment(id).join().orElseThrow(); }

        RtpPayment await(UUID id, State state) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            RtpPayment payment;
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

    private static final class Bank implements RtpEconomy.Bank {
        final CountDownLatch started = new CountDownLatch(1);
        final CompletableFuture<VaultRtpAdapter.Receipt> withdrawal = new CompletableFuture<>();
        final AtomicInteger withdrawals = new AtomicInteger();
        final AtomicInteger refunds = new AtomicInteger();
        volatile boolean current = true;
        volatile BigDecimal balance = BigDecimal.valueOf(100);
        CompletableFuture<BigDecimal> balanceReply;
        BigDecimal refundAmount;

        @Override public String identity() { return "test-bank"; }
        @Override public boolean current() { return current; }
        @Override public CompletableFuture<BigDecimal> balance(String plugin, UUID player) {
            return balanceReply == null ? CompletableFuture.completedFuture(balance) : balanceReply;
        }

        @Override public CompletableFuture<VaultRtpAdapter.Receipt> transfer(boolean refund, String plugin, UUID player, BigDecimal amount) {
            if (refund) {
                refunds.incrementAndGet();
                return CompletableFuture.completedFuture(new VaultRtpAdapter.Receipt(true, refundAmount == null ? amount : refundAmount));
            }
            withdrawals.incrementAndGet();
            started.countDown();
            return withdrawal;
        }
    }
}
