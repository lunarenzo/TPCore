package com.lunatech.tpcore.module.home.economy;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyVaultHomeAdapterTest {
    @Test
    void resolvesByUuidAndMovesFundsOnWorkerOnlyWhileRegistrationIsCurrent() {
        var uuid = UUID.randomUUID();
        var account = mock(OfflinePlayer.class);
        var plugin = mock(Plugin.class);
        var economy = mock(Economy.class);
        var services = mock(ServicesManager.class);
        var scheduler = mock(GlobalRegionScheduler.class);
        var registration = new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal, plugin);
        var global = new ArrayDeque<Runnable>();
        var worker = new ArrayDeque<Runnable>();
        when(services.getRegistration(Economy.class)).thenReturn(registration);
        when(economy.fractionalDigits()).thenReturn(2);
        when(economy.withdrawPlayer(account, 4.5)).thenReturn(new EconomyResponse(4.5, 10, EconomyResponse.ResponseType.SUCCESS, null));
        doAnswer(call -> { global.add(call.getArgument(1)); return null; }).when(scheduler).execute(eq(plugin), any());

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getOfflinePlayer(uuid)).thenReturn(account);
            var bank = LegacyVaultHomeAdapter.discover(plugin, worker::add, 15);
            assertNotNull(bank);
            var debit = bank.transfer(false, "TPCore", uuid, new BigDecimal("4.50"));
            assertTrue(worker.isEmpty());
            global.remove().run();
            verify(economy, never()).withdrawPlayer(any(OfflinePlayer.class), anyDouble());
            worker.remove().run();
            assertTrue(debit.join().successful());
            assertEquals(new BigDecimal("4.5"), debit.join().amount());

            var unrepresentable = bank.transfer(false, "TPCore", uuid, new BigDecimal("4.501"));
            global.remove().run();
            worker.remove().run();
            assertFalse(unrepresentable.join().successful());
            verify(economy, times(1)).withdrawPlayer(any(OfflinePlayer.class), anyDouble());

            var refund = bank.transfer(true, "TPCore", uuid, new BigDecimal("4.50"));
            global.remove().run();
            when(services.getRegistration(Economy.class)).thenReturn(null);
            worker.remove().run();
            assertThrows(CompletionException.class, refund::join);
            verify(economy, never()).depositPlayer(any(OfflinePlayer.class), anyDouble());
        }
    }
}
