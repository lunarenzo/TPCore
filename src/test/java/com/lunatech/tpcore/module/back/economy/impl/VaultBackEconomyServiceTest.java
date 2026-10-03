package com.lunatech.tpcore.module.back.economy.impl;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.back.economy.BackEconomyService;
import com.lunatech.tpcore.module.back.model.BackCause;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

final class VaultBackEconomyServiceTest {

    @Test
    @DisplayName("Verify NoOpBackEconomyService defaults")
    void testNoOpBackEconomyService() {
        BackEconomyService service = new NoOpBackEconomyService();
        Assertions.assertFalse(service.isAvailable());
        Assertions.assertTrue(service.has(null, 50.0));
        Assertions.assertTrue(service.withdraw(null, 50.0));
        Assertions.assertTrue(service.deposit(null, 50.0));
        Assertions.assertEquals(0.0, service.getBalance(null));
        Assertions.assertEquals("$50.00", service.format(50.0));
        Assertions.assertEquals(0.0, service.getCost(null, BackCause.TELEPORT));
        Assertions.assertTrue(service.validateFundsAsync(null, BackCause.TELEPORT).join());
        Assertions.assertTrue(service.processCostAsync(null, BackCause.TELEPORT).join());
        Assertions.assertTrue(service.chargeSuccessAsync(null, BackCause.TELEPORT).join());
        service.processRefund(null, 50.0);
        service.shutdown();
    }

    @Test
    @DisplayName("Verify VaultBackEconomyService graceful fallback when Vault plugin is absent")
    void testEconomyFallbackWithoutVault() {
        Plugin plugin = Mockito.mock(Plugin.class);
        BackConfig config = BackConfig.createDefault();
        BackEconomyService service = new VaultBackEconomyService(plugin, () -> config, LoggerFactory.getLogger("TestLogger"));

        Assertions.assertFalse(service.isAvailable());
        Assertions.assertTrue(service.has(null, 100.0));
        Assertions.assertTrue(service.withdraw(null, 100.0));
        Assertions.assertTrue(service.deposit(null, 100.0));
        Assertions.assertEquals(0.0, service.getBalance(null));
        Assertions.assertEquals("$100.00", service.format(100.0));
        Assertions.assertEquals(0.0, service.getCost(null, BackCause.TELEPORT));
        Assertions.assertTrue(service.validateFundsAsync(null, BackCause.TELEPORT).join());
        Assertions.assertTrue(service.processCostAsync(null, BackCause.TELEPORT).join());
        service.processRefund(null, 10.0);
        service.shutdown();
    }

    @Test
    @DisplayName("Verify cost calculation logic with permissions and causes")
    void testCostCalculation() {
        Plugin plugin = Mockito.mock(Plugin.class);
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.hasPermission(Permissions.BACK_BYPASS_COST)).thenReturn(false);

        BackConfig config = new BackConfig(
            true, 3, 10, 3, true, true, true, true,
            true, 15.5, 25.0, true, true,
            true, "YELLOW", "PROGRESS", "<prefix><gray>Warmup...</gray>",
            true, "<gray>Warmup...</gray>", false, "<seconds>", "<gray>Teleporting...</gray>",
            true, "minecraft:block.note_block.hat", 0.6,
            "minecraft:entity.enderman.teleport", 0.8, 1.0,
            "minecraft:block.note_block.bass", 0.8, 0.5,
            5, 10.0, true, true, true, false, true,
            BackConfig.BackSafetyConfig.createDefault(),
            BackConfig.BackStorageConfig.createDefault(),
            BackConfig.BackMessages.createDefault()
        );

        BackEconomyService service = new VaultBackEconomyService(plugin, () -> config, LoggerFactory.getLogger("TestLogger"));

        // When Vault is not active, cost returns 0.0 because isAvailable() is false
        Assertions.assertEquals(0.0, service.getCost(player, BackCause.TELEPORT));

        // When player has bypass permission
        Mockito.when(player.hasPermission(Permissions.BACK_BYPASS_COST)).thenReturn(true);
        Assertions.assertEquals(0.0, service.getCost(player, BackCause.TELEPORT));
        Assertions.assertEquals(0.0, service.getCost(player, BackCause.DEATH));
        service.shutdown();
    }
}
