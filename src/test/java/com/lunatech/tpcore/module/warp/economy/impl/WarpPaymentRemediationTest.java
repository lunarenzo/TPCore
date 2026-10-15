package com.lunatech.tpcore.module.warp.economy.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpPaymentConfig;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService.Payment;
import com.lunatech.tpcore.module.warp.model.WarpPaymentEvidence;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.WarpRefundJournal;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import com.lunatech.tpcore.module.warp.service.WarpTeleportTracker;
import java.math.BigDecimal;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import com.lunatech.tpcore.module.warp.economy.WarpTransferTracker;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpPaymentRemediationTest {
    @TempDir Path directory;
    public static final class Response {
        public final BigDecimal amount, balance;
        public final String errorMessage;
        private final boolean success;
        Response(String amount, String balance, boolean success, String error) {
            this.amount = new BigDecimal(amount); this.balance = new BigDecimal(balance); this.success = success; errorMessage = error;
        }
        public boolean transactionSuccess() { return success; }
    }
    public static final class Provider {
        CompletableFuture<BigDecimal> funds = CompletableFuture.completedFuture(new BigDecimal("100"));
        CompletableFuture<BigDecimal> afterRejection;
        final CompletableFuture<Response> debit = new CompletableFuture<>(), credit = new CompletableFuture<>();
        volatile int withdrawals, deposits;
        volatile int balanceReads;
        volatile BigDecimal deposited;
        public CompletableFuture<BigDecimal> balance(String plugin, UUID id) { return ++balanceReads > 1 && afterRejection != null ? afterRejection : funds; }
        public CompletableFuture<Response> withdraw(String plugin, UUID id, BigDecimal amount) { withdrawals++; return debit; }
        public CompletableFuture<Response> deposit(String plugin, UUID id, BigDecimal amount) { deposits++; deposited = amount; return credit; }
    }
    private static VaultUnlockedWarpAdapter adapter(Provider provider) throws Exception {
        return new VaultUnlockedWarpAdapter(provider, Provider.class, Response.class);
    }
    private WarpConfig settings() {
        WarpConfig settings = spy(WarpConfig.createDefault());
        when(settings.payments()).thenReturn(new WarpPaymentConfig(2, 2, "coins", "<amount> coins", 2, null));
        when(settings.maxConcurrentOperations()).thenReturn(1);
        return settings;
    }
    private Plugin plugin() {
        Plugin plugin = mock(Plugin.class); when(plugin.getName()).thenReturn("TPCore"); when(plugin.getDataFolder()).thenReturn(directory.toFile()); return plugin;
    }
    // A local test owner needs a reachability fence: production retains the owner in WarpModule, while native callbacks deliberately use weak references.
    private VaultWarpEconomyService service(Provider provider) throws Exception {
        WarpConfig settings = settings();
        var service = new VaultWarpEconomyService(plugin(), () -> settings, LoggerFactory.getLogger("PaymentRemediation"), adapter(provider), new WarpTransferTracker(2048));
        await(() -> service.diagnostics().journalReady()); return service;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not complete");
    }
    private static WarpPaymentEvidence unresolved() {
        return new WarpPaymentEvidence(UUID.randomUUID(), "TELEPORT", "hub", "test", "default", BigDecimal.TEN, null, null, "UNKNOWN", 0, 0, "");
    }
    @Test void stalledDebitReleasesAdmissionAndRetainsLiveEvidenceWithoutRetry() throws Exception {
        Provider bank = new Provider(); var service = service(bank);
        try {
            assertThrows(Exception.class, () -> service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").get(3, TimeUnit.SECONDS));
            await(() -> service.diagnostics().activeOperations() == 0);
            var page = service.paymentIntents(1, 10).join(); assertEquals(1, page.total());
            String evidence = page.entries().getFirst().evidence(); assertTrue(evidence.contains("warp=hub")); assertTrue(evidence.contains("provider="));
            assertFalse(service.isAvailable());
            assertThrows(Exception.class, () -> service.acknowledge(page.entries().getFirst().ticket(), UUID.randomUUID(), "bank checked").join());
            assertThrows(Exception.class, () -> service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join());
            assertTrue(service.chargeExact(UUID.randomUUID(), 0, "TELEPORT", "hub").join().successful()); assertEquals(1, bank.withdrawals); assertEquals(0, bank.deposits);
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void lateDebitIsRecordedAsUnknownAndNeverTriggersAnAutomaticRefund() throws Exception {
        Provider bank = new Provider(); var service = service(bank);
        try {
            var charge = service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub");
            await(() -> bank.withdrawals == 1);
            assertThrows(Exception.class, () -> charge.get(3, TimeUnit.SECONDS));
            bank.debit.complete(new Response("10.00000000000000000001", "90", true, "late receipt"));
            await(() -> service.paymentIntents(1, 10).join().entries().getFirst().evidence().contains("debited=10.00000000000000000001"));
            String evidence = service.paymentIntents(1, 10).join().entries().getFirst().evidence(); assertTrue(evidence.contains("phase=UNKNOWN")); assertEquals(0, bank.deposits);
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void failedWithdrawalWithLowReceiptBalanceReportsInsufficientFunds() throws Exception {
        Provider bank = new Provider(); bank.afterRejection = CompletableFuture.completedFuture(BigDecimal.ZERO);
        bank.debit.complete(new Response("0", "0", false, "balance changed")); var service = service(bank);
        try { assertFalse(service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join().successful()); assertEquals(0, service.paymentIntents(1, 10).join().total()); assertTrue(service.isAvailable()); }
        finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void unrelatedProviderRejectionPreservesReasonAndIsNotInsufficientFunds() throws Exception {
        Provider bank = new Provider(); bank.debit.complete(new Response("0", "0", false, "account frozen")); var service = service(bank);
        try {
            Exception failure = assertThrows(Exception.class, () -> service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join());
            assertTrue(failure.toString().contains("account frozen"), failure.toString()); assertTrue(service.isAvailable(), service.diagnostics().toString());
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void partialRefundRetainsActualCreditAndAcknowledgementBacksUpAndAuditsWithoutTransfer() throws Exception {
        Provider bank = new Provider(); bank.debit.complete(new Response("50", "50", true, "")); var service = service(bank);
        try {
            Payment payment = service.chargeExact(UUID.randomUUID(), 50, "CREATE", "market").join();
            bank.credit.complete(new Response("25", "75", true, "partial")); assertFalse(service.settle(payment, true).join());
            String evidence = service.paymentIntents(1, 10).join().entries().getFirst().evidence(); assertTrue(evidence.contains("credited=25")); assertTrue(evidence.contains("debited=50"));
            service.acknowledge(payment.ticket().toString(), UUID.randomUUID(), "Provider history verified; remaining credit settled externally").join();
            assertTrue(service.isAvailable()); assertEquals(0, service.paymentIntents(1, 10).join().total());
            assertTrue(Files.isRegularFile(directory.resolve("warps/refunds.properties.pre-ack.bak")));
            assertTrue(Files.readString(directory.resolve("warps/payment-resolutions.properties")).contains(payment.ticket().toString()));
            assertEquals(1, bank.withdrawals); assertEquals(1, bank.deposits);
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void duplicateSettlementNeverIssuesTwoCredits() throws Exception {
        Provider bank = new Provider(); bank.debit.complete(new Response("10", "90", true, "")); var service = service(bank);
        try {
            Payment payment = service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join();
            var first = service.settle(payment, true); var second = service.settle(payment, true); assertSame(first, second);
            await(() -> bank.deposits == 1); bank.credit.complete(new Response("10", "100", true, "")); assertTrue(first.join());
            assertThrows(Exception.class, () -> service.settle(payment, true).join()); assertEquals(1, bank.deposits);
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void compensationUsesTheExactNativeDecimalReceipt() throws Exception {
        Provider bank = new Provider(); String exact = "1.00000000000000000001";
        bank.debit.complete(new Response(exact, "99", true, "")); bank.credit.complete(new Response(exact, "100", true, "")); var service = service(bank);
        try {
            Exception failure = assertThrows(Exception.class, () -> service.chargeExact(UUID.randomUUID(), 1, "TELEPORT", "hub").join());
            Throwable root = failure; while (root.getCause() != null) root = root.getCause();
            assertTrue(failure.toString().contains("compensation result=true"), root.toString());
            assertEquals(new BigDecimal(exact), bank.deposited); assertEquals(0, service.paymentIntents(1, 10).join().total(), root.toString());
            assertEquals(1, bank.withdrawals); assertEquals(1, bank.deposits);
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void uncertainTeleportRetainsPaymentEvidenceWithoutAutomaticRefund() throws Exception {
        Provider bank = new Provider(); bank.debit.complete(new Response("10", "90", true, "")); var service = service(bank);
        try {
            Payment payment = service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join();
            service.retainUncertain(payment, "Teleport ticket example outcome unknown");
            var page = service.paymentIntents(1, 10).join();
            assertEquals(1, page.total()); assertTrue(page.entries().getFirst().evidence().contains("UNKNOWN"));
            assertTrue(page.entries().getFirst().evidence().contains("outcome unknown"));
            assertFalse(service.isAvailable()); assertThrows(Exception.class, () -> service.settle(payment, true).join());
            assertEquals(0, bank.deposits);
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void replacementProviderCannotRefundAnOriginalProvidersReceipt() throws Exception {
        Provider original = new Provider(), replacement = new Provider(); original.debit.complete(new Response("10", "90", true, "")); var service = service(original);
        try {
            Payment payment = service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join(); service.replaceProvider(adapter(replacement));
            assertThrows(Exception.class, () -> service.settle(payment, true).join());
            assertEquals(0, replacement.deposits); assertEquals(0, original.deposits); assertFalse(service.isAvailable());
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void providerRegistrationAfterStartupMakesNewPaymentsAvailable() throws Exception {
        WarpConfig settings = settings();
        var service = new VaultWarpEconomyService(plugin(), () -> settings, LoggerFactory.getLogger("LateProvider"), null, new WarpTransferTracker(2048));
        try { await(() -> service.diagnostics().journalReady()); assertFalse(service.isAvailable()); service.replaceProvider(adapter(new Provider())); assertTrue(service.isAvailable()); }
        finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void balanceTimeoutCanBeResumedWithoutAnyMoneyTransfer() throws Exception {
        Provider bank = new Provider(); bank.funds = new CompletableFuture<>(); var service = service(bank);
        try {
            assertThrows(Exception.class, () -> service.validateExact(UUID.randomUUID(), 10).get(3, TimeUnit.SECONDS));
            await(() -> service.diagnostics().activeOperations() == 0); service.resume().join(); assertTrue(service.isAvailable());
            bank.funds.complete(new BigDecimal("100")); assertEquals(0, bank.withdrawals); assertEquals(0, bank.deposits);
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void precisionValidationPreventsNonzeroPricesDisplayingAsZero() {
        var money = WarpPaymentConfig.createDefault(); assertThrows(IllegalArgumentException.class, () -> money.amount(.001));
        var thousandths = new WarpPaymentConfig(1, 3, "coins", "<amount> coins", 2, null);
        assertEquals("0.001 coins", thousandths.display(thousandths.amount(.001)));
    }
    @Test void paidCreationTimeoutReleasesGameplayCapacityAndFreeCreationStillWorks() throws Exception {
        Provider bank = spy(new Provider()); WarpConfig paid = settings(); when(paid.economyEnabled()).thenReturn(true); when(paid.setWarpCost()).thenReturn(10.0);
        WarpConfig free = settings(); AtomicReference<WarpConfig> config = new AtomicReference<>(paid); Plugin plugin = plugin();
        var economy = new VaultWarpEconomyService(plugin, config::get, LoggerFactory.getLogger("Capacity"), adapter(bank), new WarpTransferTracker(2048));
        WarpRepository repository = mock(WarpRepository.class); when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of())); when(repository.save(any())).thenReturn(CompletableFuture.completedFuture(null)); when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
        var service = new DefaultWarpService(plugin, repository, new DefaultWarpCache(), economy, paid, LoggerFactory.getLogger("Capacity"), new WarpTeleportTracker(paid.maxPlayerStateEntries()));
        Player creator = mock(Player.class); World world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.getName()).thenReturn("world");
        when(creator.getUniqueId()).thenReturn(UUID.randomUUID()); when(creator.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(creator.isOnline()).thenReturn(true);
        try {
            service.initialize().join(); await(() -> economy.diagnostics().journalReady());
            assertEquals(WarpResultStatus.ECONOMY_UNAVAILABLE, service.setWarp(creator, "paid", false, null, null).get(3, TimeUnit.SECONDS));
            var order = inOrder(creator, bank); order.verify(creator).sendMessage(any(Component.class)); order.verify(bank).withdraw(anyString(), any(UUID.class), eq(new BigDecimal("10.00")));
            assertEquals(0, service.diagnostics().pendingOperations()); config.set(free); service.updateConfig(free);
            assertEquals(WarpResultStatus.SUCCESS, service.setWarp(creator, "free", false, null, null).get(3, TimeUnit.SECONDS));
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void evidenceCodecPreservesExactDecimalsAndRejectsExpansionBombs() {
        long now = System.currentTimeMillis();
        var evidence = new WarpPaymentEvidence(UUID.randomUUID(), "TELEPORT", "hub", "provider", "default", new BigDecimal("1.25"), new BigDecimal("1.25000000000000000001"), null, "UNKNOWN", now, now, "reason | with separators");
        assertEquals(evidence, WarpPaymentEvidence.decode(evidence.encode()));
        assertThrows(IllegalArgumentException.class, () -> new WarpPaymentEvidence(UUID.randomUUID(), "", "", "", "", new BigDecimal("1E+1000000000"), null, null, "UNKNOWN", 0, 0, ""));
    }
    @Test void acknowledgementRequiresBackupAndHistoryRemainsBounded() throws Exception {
        Path folder = directory.resolve("journal"); Files.createDirectories(folder);
        WarpRefundJournal journal = new WarpRefundJournal(folder.resolve("refunds.properties"), 8, 2, 2);
        try {
            journal.ready().join(); UUID actor = UUID.randomUUID();
            for (int i = 0; i < 3; i++) {
                UUID ticket = journal.record(unresolved()).join(); journal.acknowledge(ticket.toString(), actor, "Verified bank history", () -> false).join();
                Properties current = new Properties(); try (var input = Files.newInputStream(folder.resolve("payment-resolutions.properties"))) { current.load(input); }
                assertTrue(current.containsKey(ticket.toString()));
            }
            Properties audit = new Properties(); try (var input = Files.newInputStream(folder.resolve("payment-resolutions.properties"))) { audit.load(input); }
            assertEquals(2, audit.size());
            UUID ticket = journal.record(unresolved()).join();
            assertThrows(Exception.class, () -> journal.acknowledge(ticket.toString(), actor, "x".repeat(257), () -> false).join());
            Files.delete(folder.resolve("refunds.properties.pre-ack.bak")); Files.createDirectory(folder.resolve("refunds.properties.pre-ack.bak"));
            Files.writeString(folder.resolve("refunds.properties.pre-ack.bak/keep"), "non-empty directory");
            assertThrows(Exception.class, () -> journal.acknowledge(ticket.toString(), actor, "Verified bank history", () -> false).join());
            assertEquals(1, journal.pending());
        } finally { journal.close().join(); }
    }
    @Test void startupPreservesModernEvidenceAndAcknowledgementDoesNotReplayTransfers() throws Exception {
        Provider original = new Provider(); original.debit.complete(new Response("10", "90", true, "")); var first = service(original);
        Payment receipt = first.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join(); first.close().join();
        Provider replacement = new Provider(); var reopened = service(replacement);
        try {
            assertFalse(reopened.isAvailable()); String evidence = reopened.paymentIntents(1, 10).join().entries().getFirst().evidence();
            assertTrue(evidence.contains("warp=hub")); assertTrue(evidence.contains("phase=UNKNOWN")); assertTrue(evidence.contains("debited=10"));
            reopened.acknowledge(receipt.ticket().toString(), UUID.randomUUID(), "Verified original provider history externally").join();
            assertTrue(reopened.isAvailable()); assertEquals(0, replacement.withdrawals); assertEquals(0, replacement.deposits);
        } finally { reopened.close().join(); Reference.reachabilityFence(reopened); }
    }
    @Test void settlementRejectsChangesToTheCapturedPlayerOrAmount() throws Exception {
        Provider bank = new Provider(); bank.debit.complete(new Response("10", "90", true, "")); var service = service(bank);
        try {
            Payment payment = service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join();
            assertThrows(Exception.class, () -> service.acknowledge(payment.ticket().toString(), UUID.randomUUID(), "Cannot resolve a funded active warp").join());
            assertThrows(Exception.class, () -> service.settle(new Payment(payment.playerId(), 99, true, payment.ticket()), true).join());
            assertThrows(Exception.class, () -> service.settle(new Payment(UUID.randomUUID(), payment.exactAmount(), true, payment.ticket()), true).join());
            assertEquals(0, bank.deposits); assertTrue(service.settle(payment, false).join());
        } finally { service.close().join(); Reference.reachabilityFence(service); }
    }
    @Test void liveTransferCannotBeAcknowledgedAcrossAModuleEconomyRestart() throws Exception {
        Provider bank = new Provider(); WarpTransferTracker tracker = new WarpTransferTracker(2);
        WarpConfig settings = settings();
        var first = new VaultWarpEconomyService(plugin(), () -> settings, LoggerFactory.getLogger("BeforeRestart"), adapter(bank), tracker);
        await(() -> first.diagnostics().journalReady());
        var charge = first.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub"); await(() -> bank.withdrawals == 1);
        assertThrows(Exception.class, () -> charge.get(3, TimeUnit.SECONDS));
        String ticket = first.paymentIntents(1, 10).join().entries().getFirst().ticket(); first.close().join();
        var next = new VaultWarpEconomyService(plugin(), () -> settings, LoggerFactory.getLogger("AfterRestart"), adapter(new Provider()), tracker);
        try {
            await(() -> next.diagnostics().journalReady());
            assertThrows(Exception.class, () -> next.acknowledge(ticket, UUID.randomUUID(), "Still live; must refuse").join());
            bank.debit.complete(new Response("10", "90", true, "completed after module restart"));
            await(tracker::isEmpty);
            next.acknowledge(ticket, UUID.randomUUID(), "Verified completed original bank transaction externally").join();
            assertTrue(next.isAvailable()); assertEquals(0, bank.deposits);
        } finally { next.close().join(); Reference.reachabilityFence(next); }
    }
}
