package com.lunatech.tpcore.module.spawn;

import com.lunatech.tpcore.module.spawn.economy.SpawnEconomyService;
import com.lunatech.tpcore.module.spawn.economy.impl.NoOpSpawnEconomyService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class SpawnEconomyServiceTest {

    @Test
    void testNoOpEconomyService() throws ExecutionException, InterruptedException {
        SpawnEconomyService service = new NoOpSpawnEconomyService();
        assertFalse(service.isAvailable());
        assertEquals(0.0, service.getBalance(null));
        assertEquals("$10.00", service.format(10.0));
        assertEquals(0.0, service.getCost(null));
        assertTrue(service.has(null, 100.0));
        assertTrue(service.withdraw(null, 50.0));
        assertTrue(service.deposit(null, 50.0));
        assertTrue(service.processTeleportCostAsync(null).get());
    }
}
