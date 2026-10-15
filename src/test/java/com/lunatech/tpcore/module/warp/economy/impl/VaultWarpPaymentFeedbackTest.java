package com.lunatech.tpcore.module.warp.economy.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import java.nio.file.Path;
import com.lunatech.tpcore.module.warp.economy.WarpTransferTracker;
import java.nio.file.Files;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VaultWarpPaymentFeedbackTest {
    @TempDir Path directory;

    private VaultWarpEconomyService service(VaultUnlockedWarpAdapterTest.NativeProvider provider) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("TPCore"); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var adapter = new VaultUnlockedWarpAdapter(provider, VaultUnlockedWarpAdapterTest.NativeProvider.class, VaultUnlockedWarpAdapterTest.Receipt.class);
        return new VaultWarpEconomyService(plugin, WarpConfig::createDefault, LoggerFactory.getLogger("PaymentFeedback"), adapter, new WarpTransferTracker(2048));
    }

    @Test void insufficientBalanceDoesNotStartAWithdrawalOrCreateAnIntent() throws Exception {
        var provider = new VaultUnlockedWarpAdapterTest.NativeProvider();
        var service = service(provider);
        try {
            assertFalse(service.chargeExact(UUID.randomUUID(), 101, "TELEPORT", "hub").join().successful());
            assertFalse(provider.withdrawal.isDone());
            assertEquals(0, service.paymentIntents(1, 10).join().total());
        } finally { service.close().join(); }
    }

    @Test void providerRejectionAndMismatchedReceiptsAreNotReportedAsInsufficientFunds() throws Exception {
        var provider = new VaultUnlockedWarpAdapterTest.NativeProvider();
        var service = service(provider);
        try {
            provider.withdrawal.complete(new VaultUnlockedWarpAdapterTest.Receipt("0", false));
            assertThrows(RuntimeException.class, () -> service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join());
            assertEquals(0, service.paymentIntents(1, 10).join().total());
            assertFalse(service.diagnostics().reconciliationRequired());
        } finally { service.close().join(); }
        provider = new VaultUnlockedWarpAdapterTest.NativeProvider();
        var partial = service(provider);
        try {
            provider.withdrawal.complete(new VaultUnlockedWarpAdapterTest.Receipt("5", true));
            provider.credit.complete(new VaultUnlockedWarpAdapterTest.Receipt("5", true));
            assertThrows(RuntimeException.class, () -> partial.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join());
            assertEquals(0, partial.paymentIntents(1, 10).join().total());
            assertFalse(partial.diagnostics().reconciliationRequired());
        } finally { partial.close().join(); }
    }

    @Test void startupReconciliationBlocksChargesBeforeAnyProviderCall() throws Exception {
        Files.createDirectories(directory.resolve("warps"));
        Files.writeString(directory.resolve("warps/refunds.properties"), UUID.randomUUID() + "=" + UUID.randomUUID() + "|10");
        var provider = new VaultUnlockedWarpAdapterTest.NativeProvider();
        var service = service(provider);
        try {
            assertThrows(RuntimeException.class, () -> service.chargeExact(UUID.randomUUID(), 10, "TELEPORT", "hub").join());
            assertFalse(provider.withdrawal.isDone());
            assertEquals(1, service.paymentIntents(1, 10).join().total());
            assertTrue(service.diagnostics().reconciliationRequired()); assertFalse(service.isAvailable());
        } finally { service.close().join(); }
    }
}
