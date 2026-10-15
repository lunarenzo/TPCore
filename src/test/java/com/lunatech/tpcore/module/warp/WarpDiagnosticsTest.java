package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics.PaymentIntent;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics.PaymentPage;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.WarpRefundJournal;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpDiagnosticsTest {
    @TempDir Path directory;

    @Test void journalPagesAreOrderedClampedImmutableAndReadOnly() throws Exception {
        Path file = directory.resolve("refunds.properties");
        WarpRefundJournal journal = new WarpRefundJournal(file, 3, 2);
        try {
            journal.ready().join();
            journal.record(UUID.randomUUID(), 12.5).join();
            journal.record(UUID.randomUUID(), 7).join();
            journal.record(UUID.randomUUID(), 3).join();
            byte[] before = Files.readAllBytes(file);
            PaymentPage all = journal.snapshot(-1, Integer.MAX_VALUE).join();
            assertEquals(1, all.page()); assertEquals(3, all.total());
            assertEquals(all.entries().stream().map(PaymentIntent::ticket).sorted().toList(), all.entries().stream().map(PaymentIntent::ticket).toList());
            PaymentPage last = journal.snapshot(Integer.MAX_VALUE, 1).join();
            assertEquals(3, last.page()); assertEquals(3, last.pages()); assertEquals(1, last.entries().size());
            assertThrows(UnsupportedOperationException.class, () -> all.entries().clear());
            assertArrayEquals(before, Files.readAllBytes(file)); assertEquals(3, journal.pending());
        } finally { journal.close().join(); }
    }

    @Test void malformedLegacyEvidenceIsPreservedAndFailedJournalIsNotEmpty() throws Exception {
        Path file = directory.resolve("legacy.properties");
        Files.writeString(file, "legacy-ticket=unexpected-record\n");
        WarpRefundJournal journal = new WarpRefundJournal(file, 2, 1);
        try {
            journal.ready().join();
            assertEquals(new PaymentIntent("legacy-ticket", "unexpected-record"), journal.snapshot(1, 1).join().entries().getFirst());
        } finally { journal.close().join(); }
        Files.writeString(file, "x".repeat(513));
        WarpRefundJournal broken = new WarpRefundJournal(file, 2, 1);
        try {
            assertThrows(RuntimeException.class, () -> broken.ready().join());
            assertFalse(broken.isReady());
            assertThrows(RuntimeException.class, () -> broken.snapshot(1, 1).join());
        } finally { broken.close().join(); }
        Files.writeString(file, "ticket=" + "x".repeat(257));
        WarpRefundJournal oversized = new WarpRefundJournal(file, 10, 1);
        try {
            assertThrows(RuntimeException.class, () -> oversized.ready().join());
            assertThrows(RuntimeException.class, () -> oversized.snapshot(1, 1).join());
        } finally { oversized.close().join(); }
    }

    @Test void statusReadsCountersWithoutQueryingPersistence() {
        WarpRepository repository = mock(WarpRepository.class);
        when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.loadAll()).thenReturn(CompletableFuture.completedFuture(Map.of()));
        when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
        WarpConfig settings = WarpConfig.createDefault();
        WarpEconomyService economy = mock(WarpEconomyService.class);
        when(economy.diagnostics()).thenReturn(new WarpDiagnostics.Economy(false, false, false, 0, 0, 0));
        when(economy.close()).thenReturn(CompletableFuture.completedFuture(null));
        DefaultWarpService service = new DefaultWarpService(mock(Plugin.class), repository, new DefaultWarpCache(),
            economy, settings, LoggerFactory.getLogger("WarpDiagnosticsTest"));
        try {
            assertFalse(service.diagnostics().ready());
            service.initialize().join(); clearInvocations(repository);
            WarpDiagnostics status = service.diagnostics();
            assertTrue(status.ready()); assertEquals(0, status.warps());
            assertEquals(settings.maxConcurrentOperations(), status.operationCapacity());
            assertEquals(0, status.pendingOperations()); assertEquals(settings.maxCachedWarps(), status.cacheCapacity());
            verifyNoInteractions(repository);
        } finally { service.close().join(); }
    }

    @Test void controlFailuresKeepTheirSpecificMeaning() throws Exception {
        WarpConfig settings = WarpConfig.createDefault();
        WarpCommandRegistry registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> null, () -> settings, null);
        Method mapping = WarpCommandRegistry.class.getDeclaredMethod("controlResult", WarpConfig.class, WarpResultStatus.class);
        mapping.setAccessible(true);
        Map<WarpResultStatus, String> expected = Map.of(
            WarpResultStatus.INSUFFICIENT_FUNDS, settings.messages().insufficientFunds(),
            WarpResultStatus.ECONOMY_UNAVAILABLE, settings.messages().economyUnavailable(),
            WarpResultStatus.WORLD_NOT_LOADED, settings.messages().worldNotLoaded(),
            WarpResultStatus.UNSAFE_LOCATION, settings.messages().unsafeLocation(),
            WarpResultStatus.BUSY, settings.messages().operationBusy(),
            WarpResultStatus.WARP_CLOSED, settings.controlMessages().closed(),
            WarpResultStatus.WARP_NOT_FOUND, settings.messages().warpNotFound(),
            WarpResultStatus.ERROR, settings.messages().operationFailed());
        for (var entry : expected.entrySet()) assertEquals(entry.getValue(), mapping.invoke(registry, settings, entry.getKey()));
    }

    private void status(WarpCommandRegistry registry, CommandSender sender, boolean payments) throws Exception {
        Method method = WarpCommandRegistry.class.getDeclaredMethod("warpStatus", CommandSender.class, boolean.class, int.class);
        method.setAccessible(true); method.invoke(registry, sender, payments, 1);
    }

    @Test void evidencePermissionIsRequiredAndRecheckedBeforeDelivery() throws Exception {
        WarpService service = mock(WarpService.class);
        JavaPlugin plugin = mock(JavaPlugin.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(Permissions.WARP_STATUS)).thenReturn(true);
        CompletableFuture<PaymentPage> read = new CompletableFuture<>();
        when(service.paymentIntents(anyInt(), anyInt())).thenReturn(read);
        WarpCommandRegistry registry = new WarpCommandRegistry(plugin, () -> service, WarpConfig::createDefault, null);
        try (var bukkit = mockStatic(Bukkit.class)) {
            GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).execute(any(), any());
            status(registry, sender, true);
            verify(service, never()).paymentIntents(anyInt(), anyInt());
            when(sender.hasPermission(Permissions.WARP_PAYMENTS)).thenReturn(true);
            status(registry, sender, true);
            clearInvocations(sender);
            when(sender.hasPermission(Permissions.WARP_PAYMENTS)).thenReturn(false);
            read.complete(new PaymentPage(1, 1, 1, List.of(new PaymentIntent("secret", "player|12"))));
            verify(sender, never()).sendMessage(any(Component.class));
        }
    }

    @Test void evidenceCannotInjectFormattingAndNeverInvokesARecoveryOperation() throws Exception {
        WarpService service = mock(WarpService.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(service.paymentIntents(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(
            new PaymentPage(1, 1, 1, List.of(new PaymentIntent("ticket", "<red>literal</red>|12")))));
        List<Component> replies = new ArrayList<>();
        doAnswer(call -> { replies.add(call.getArgument(0)); return null; }).when(sender).sendMessage(any(Component.class));
        WarpCommandRegistry registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, WarpConfig::createDefault, null);
        try (var bukkit = mockStatic(Bukkit.class)) {
            GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).execute(any(), any());
            status(registry, sender, true);
            assertTrue(replies.stream().anyMatch(reply -> reply.toString().contains("<red>literal</red>|12")));
            verify(service).paymentIntents(1, WarpConfig.createDefault().warpsPerPage());
            verifyNoMoreInteractions(service);
        }
    }

    @Test void registeredStatusTreeEnforcesBothPermissionsAndRejectsInvalidPages() throws Exception {
        WarpService service = mock(WarpService.class);
        CommandSender sender = mock(CommandSender.class);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        Commands registrar = mock(Commands.class);
        doAnswer(call -> { LiteralCommandNode<CommandSourceStack> node = call.getArgument(0); dispatcher.getRoot().addChild(node); return Set.of(); })
            .when(registrar).register(any(LiteralCommandNode.class));
        WarpCommandRegistry registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, WarpConfig::createDefault, null);
        Method register = WarpCommandRegistry.class.getDeclaredMethod("registerStaffCommands", Commands.class);
        register.setAccessible(true); register.invoke(registry, registrar);
        assertThrows(Exception.class, () -> dispatcher.execute("warpstatus", source));
        when(sender.hasPermission(Permissions.WARP_STATUS)).thenReturn(true);
        assertThrows(Exception.class, () -> dispatcher.execute("warpstatus payments", source));
        when(sender.hasPermission(Permissions.WARP_PAYMENTS)).thenReturn(true);
        assertThrows(Exception.class, () -> dispatcher.execute("warpstatus payments 0", source));
        when(service.paymentIntents(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(new PaymentPage(1, 1, 0, List.of())));
        try (var bukkit = mockStatic(Bukkit.class)) {
            GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).execute(any(), any());
            dispatcher.execute("warpstatus payments 2", source);
            verify(service).paymentIntents(2, WarpConfig.createDefault().warpsPerPage());
            assertThrows(Exception.class, () -> dispatcher.execute("warpstatus payments retry", source));
        }
    }
}
