package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.impl.NoOpWarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.impl.VaultWarpEconomyService;
import java.util.concurrent.ExecutionException;
import java.util.UUID;
import java.io.File;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VaultWarpEconomyServiceTest {
    @TempDir File directory;

    @Test
    @DisplayName("NoOpWarpEconomyService behaves correctly as null-object fallback")
    void testNoOpEconomyService() throws ExecutionException, InterruptedException {
        WarpEconomyService service = new NoOpWarpEconomyService();
        assertFalse(service.isAvailable());
        assertEquals("$10.00", service.format(10.0));
        assertEquals(0.0, service.getWarpCost(null));
        assertEquals(0.0, service.getSetWarpCost(null));
        assertTrue(service.processWarpCostAsync(null).get());
        assertTrue(service.processSetWarpCostAsync(null).get());
        assertTrue(service.validateWarpFundsAsync(null).get());
        assertTrue(service.chargeSuccessAsync(null).get());
    }

    @Test
    @DisplayName("VaultWarpEconomyService gracefully handles absent Vault environment")
    void testVaultFallbackWithoutVault() throws ExecutionException, InterruptedException {
        Plugin mockPlugin = mock(Plugin.class);
        when(mockPlugin.getDataFolder()).thenReturn(directory);
        when(mockPlugin.getName()).thenReturn("TPCore");
        WarpConfig config = new WarpConfig(
            true, 3, 10, true, true, false, "general", 8, 100, true, 25.0, 100.0,
            "CHARGE_ON_WARMUP", true, WarpConfig.WarpSafetyConfig.createDefault(),
            WarpConfig.WarpStorageConfig.createDefault(), WarpConfig.WarpMessages.createDefault()
        );

        VaultWarpEconomyService service = new VaultWarpEconomyService(
            mockPlugin, () -> config, LoggerFactory.getLogger("Test-VaultWarpEconomy")
        );

        assertFalse(service.isAvailable());
        assertEquals("$25.00", service.format(25.0));

        Player player = mock(Player.class);
        when(player.hasPermission(Permissions.WARP_ADMIN)).thenReturn(false);
        when(player.hasPermission(Permissions.WARP_BYPASS_COST)).thenReturn(false);
        when(player.hasPermission(Permissions.WARP_BYPASS_COST_WARP)).thenReturn(false);
        when(player.hasPermission(Permissions.WARP_BYPASS_COST_SETWARP)).thenReturn(false);

        // Configured paid warps fail closed when no native async provider is available.
        assertEquals(25.0, service.getWarpCost(player));
        assertEquals(100.0, service.getSetWarpCost(player));
        assertThrows(ExecutionException.class, () -> service.validateWarpFundsAsync(player).get());
        assertThrows(ExecutionException.class, () -> service.processWarpCostAsync(player).get());
        assertThrows(ExecutionException.class, () -> service.processSetWarpCostAsync(player).get());
        assertThrows(ExecutionException.class, () -> service.chargeSuccessAsync(player).get());

        service.close().join();
    }
}
