package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpEdit;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpEditingCommandTest {
    private CommandDispatcher<CommandSourceStack> dispatcher(WarpService service) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        Commands commands = mock(Commands.class);
        doAnswer(invocation -> {
            LiteralCommandNode<CommandSourceStack> node = invocation.getArgument(0);
            dispatcher.getRoot().addChild(node);
            return Set.of();
        }).when(commands).register(any());
        WarpCommandRegistry registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, WarpConfig::createDefault);
        Method method = WarpCommandRegistry.class.getDeclaredMethod("registerStaffCommands", Commands.class);
        method.setAccessible(true); method.invoke(registry, commands);
        return dispatcher;
    }

    @Test void categoryOnlyStaffCannotRenameOrSetPasswords() throws Exception {
        WarpService service = mock(WarpService.class);
        when(service.editWarp(anyString(), any())).thenReturn(CompletableFuture.completedFuture(WarpResultStatus.SUCCESS));
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(Permissions.WARP_EDIT_CATEGORY)).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(service);
            assertEquals(1, dispatcher.execute("editwarp hub category market", source));
            verify(service).editWarp("hub", new WarpEdit.Category("market"));
            assertThrows(Exception.class, () -> dispatcher.execute("editwarp hub rename other", source));
            assertThrows(Exception.class, () -> dispatcher.execute("editwarp hub password set secret", source));
            verify(service, times(1)).editWarp(anyString(), any());
        }
    }

    @Test void availabilityStaffCanUsePlainMultiwordReasonButCannotChangeVisibility() throws Exception {
        WarpService service = mock(WarpService.class);
        when(service.editWarp(anyString(), any())).thenReturn(CompletableFuture.completedFuture(WarpResultStatus.SUCCESS));
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission(Permissions.WARP_EDIT_AVAILABILITY)).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            var commands = dispatcher(service);
            commands.execute("editwarp hub close Unsafe <red>bridge under construction", source);
            commands.execute("editwarp hub open", source);
            verify(service).editWarp("hub", new WarpEdit.Close("Unsafe <red>bridge under construction"));
            verify(service).editWarp("hub", new WarpEdit.Open());
            assertThrows(Exception.class, () -> commands.execute("editwarp hub unlisted", source));
            assertThrows(Exception.class, () -> commands.execute("warprecover list", source));
        }
    }

    @Test void publicDiscoveryOmitsUnlistedWarpButShowsClosedWarpWithBadge() throws Exception {
        WarpService service = mock(WarpService.class);
        when(service.isReady()).thenReturn(true);
        Warp hidden = new Warp("secret", null, "world", 0, 64, 0, 0, 0, null, "hidden", null, false, 0, false, "", true);
        Warp closed = new Warp("hub", null, "world", 0, 64, 0, 0, 0, null, "general", null, false, 0, true, "Unsafe bridge", false);
        when(service.getAllWarps()).thenReturn(List.of(hidden, closed));
        CommandSender sender = mock(CommandSender.class);
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> { messages.add(((Component) invocation.getArgument(0)).toString()); return null; }).when(sender).sendMessage(any(Component.class));
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        WarpCommandRegistry registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, WarpConfig::createDefault);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            Method list = WarpCommandRegistry.class.getDeclaredMethod("executeWarpsList", CommandSender.class, String.class, int.class);
            list.setAccessible(true); list.invoke(registry, sender, null, 1);
        }
        String output = String.join("", messages);
        assertFalse(output.contains("secret")); assertFalse(output.contains("hidden")); assertTrue(output.contains("hub")); assertTrue(output.contains("[Closed]"));
    }

    @Test void deletionCommandRequiresPreparationAndUsesConfirmationRatherThanDirectDelete() throws Exception {
        WarpService service = mock(WarpService.class);
        UUID consoleActor = new UUID(0, 0);
        when(service.prepareDeletion(consoleActor, "hub")).thenReturn(WarpResultStatus.CONFIRMATION_REQUIRED);
        when(service.confirmDeletion(consoleActor, "hub")).thenReturn(CompletableFuture.completedFuture(WarpResultStatus.SUCCESS));
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission(Permissions.WARP_DEL)).thenReturn(true);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        WarpCommandRegistry registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, WarpConfig::createDefault);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            Method delete = WarpCommandRegistry.class.getDeclaredMethod("executeDelWarp", CommandSender.class, String.class, boolean.class);
            delete.setAccessible(true); delete.invoke(registry, sender, "hub", false);
            verify(service, never()).confirmDeletion(any(), anyString()); verify(service, never()).deleteWarp(anyString());
            delete.invoke(registry, sender, "hub", true); verify(service).confirmDeletion(consoleActor, "hub");
            verify(service, never()).deleteWarp(anyString());
        }
    }

    @Test void recoveryCommandDisplaysTicketsWithoutRevealingPasswordsAndRestoresById() throws Exception {
        WarpService service = mock(WarpService.class);
        Warp warp = new Warp("hub", null, "world", 0, 64, 0, 0, 0, null, "general", "PRIVATE_HASH", true, 0);
        DeletedWarp entry = new DeletedWarp(UUID.randomUUID(), warp, System.currentTimeMillis(), UUID.randomUUID());
        when(service.getDeletedWarps()).thenReturn(CompletableFuture.completedFuture(List.of(entry)));
        when(service.restoreWarp(entry.id())).thenReturn(CompletableFuture.completedFuture(WarpResultStatus.SUCCESS));
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission(Permissions.WARP_RECOVER)).thenReturn(true);
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> { messages.add(((Component) invocation.getArgument(0)).toString()); return null; }).when(sender).sendMessage(any(Component.class));
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            var commands = dispatcher(service);
            commands.execute("warprecover list", source); commands.execute("warprecover " + entry.id(), source);
            verify(service).restoreWarp(entry.id());
        }
        String output = String.join("", messages);
        assertTrue(output.contains(entry.id().toString())); assertTrue(output.contains("Restored")); assertFalse(output.contains("PRIVATE_HASH"));
    }

    @Test void infoShowsMetadataButNeverThePasswordHash() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true);
        Warp warp = new Warp("hub", UUID.randomUUID(), "world", 12, 64, -9, 90, 0, UUID.randomUUID(), "market", "SENSITIVE_HASH_NOT_FOR_DISPLAY", true, 12345);
        when(service.getWarp("hub")).thenReturn(Optional.of(warp));
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission(Permissions.WARP_INFO)).thenReturn(true);
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> { messages.add(((Component) invocation.getArgument(0)).toString()); return null; }).when(sender).sendMessage(any(Component.class));
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            assertEquals(1, dispatcher(service).execute("warpinfo hub", source));
        }
        assertFalse(messages.isEmpty()); String output = String.join("", messages);
        assertTrue(output.contains("market")); assertTrue(output.contains("tpcore.warp.hub"));
        assertFalse(output.contains(warp.passwordHash())); verify(service, never()).editWarp(anyString(), any());
    }

    @Test void consoleCanEditPasswordsAndPermissionsButCannotRelocate() throws Exception {
        WarpService service = mock(WarpService.class);
        when(service.editWarp(anyString(), any())).thenReturn(CompletableFuture.completedFuture(WarpResultStatus.SUCCESS));
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission(Permissions.WARP_ADMIN)).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(service);
            dispatcher.execute("editwarp hub password set \"two words\"", source);
            dispatcher.execute("editwarp hub password clear", source);
            dispatcher.execute("editwarp hub permission required", source);
            dispatcher.execute("editwarp hub permission public", source);
            dispatcher.execute("editwarp hub relocate", source);
            verify(service).editWarp("hub", new WarpEdit.Password("two words"));
            verify(service).editWarp("hub", new WarpEdit.ClearPassword());
            verify(service).editWarp("hub", new WarpEdit.Permission(true));
            verify(service).editWarp("hub", new WarpEdit.Permission(false));
            verify(service, times(4)).editWarp(anyString(), any());
        }
    }
}
