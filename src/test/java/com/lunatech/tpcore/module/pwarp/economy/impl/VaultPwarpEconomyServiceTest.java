package com.lunatech.tpcore.module.pwarp.economy.impl;

import com.lunatech.tpcore.module.pwarp.economy.PwarpEconomyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class VaultPwarpEconomyServiceTest {

    @Test
    @DisplayName("Verify economy service graceful fallback when Vault plugin is absent")
    void testEconomyFallbackWithoutVault() {
        PwarpEconomyService service = new VaultPwarpEconomyService(LoggerFactory.getLogger("TestLogger"));

        Assertions.assertFalse(service.isAvailable());
        Assertions.assertTrue(service.has(null, 100.0));
        Assertions.assertTrue(service.withdraw(null, 100.0));
        Assertions.assertTrue(service.deposit(null, 100.0));
        Assertions.assertEquals("$100.00", service.format(100.0));
    }
}
