package com.lunatech.tpcore.module.warp.economy.impl;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultUnlockedWarpAdapterTest {
    public static final class Receipt {
        public final BigDecimal amount;
        private final boolean success;
        Receipt(String amount, boolean success) { this.amount = new BigDecimal(amount); this.success = success; }
        public boolean transactionSuccess() { return success; }
    }
    public static final class NativeProvider {
        CompletableFuture<Receipt> withdrawal = new CompletableFuture<>();
        CompletableFuture<Receipt> credit = new CompletableFuture<>();
        public CompletableFuture<Receipt> withdraw(String pluginName, UUID id, BigDecimal amount) { return withdrawal; }
        public CompletableFuture<Receipt> deposit(String pluginName, UUID id, BigDecimal amount) { return credit; }
        public CompletableFuture<BigDecimal> balance(String pluginName, UUID id) { return CompletableFuture.completedFuture(new BigDecimal("100")); }
    }

    @Test void capturesActualDebitAndDoesNotBlockOnTheProviderFuture() throws Exception {
        NativeProvider provider = new NativeProvider();
        VaultUnlockedWarpAdapter adapter = new VaultUnlockedWarpAdapter(provider, NativeProvider.class, Receipt.class);
        CompletableFuture<Double> result = adapter.withdraw("TPCore", UUID.randomUUID(), 50.0);
        assertFalse(result.isDone());
        provider.withdrawal.complete(new Receipt("25.00", true));
        assertEquals(25.0, result.join());
    }

    @Test void partialCreditsRemainUnresolvedRatherThanReportingAFullRefund() throws Exception {
        NativeProvider provider = new NativeProvider();
        VaultUnlockedWarpAdapter adapter = new VaultUnlockedWarpAdapter(provider, NativeProvider.class, Receipt.class);
        CompletableFuture<Boolean> result = adapter.deposit("TPCore", UUID.randomUUID(), 50.0);
        provider.credit.complete(new Receipt("25", true));
        assertFalse(result.join());
    }

    @Test void failedDebitsAreDistinguishedFromSuccessfulZeroDebits() throws Exception {
        NativeProvider provider = new NativeProvider();
        VaultUnlockedWarpAdapter adapter = new VaultUnlockedWarpAdapter(provider, NativeProvider.class, Receipt.class);
        provider.withdrawal.complete(new Receipt("0", false));
        assertEquals(-1.0, adapter.withdraw("TPCore", UUID.randomUUID(), 50.0).join());
    }
}
