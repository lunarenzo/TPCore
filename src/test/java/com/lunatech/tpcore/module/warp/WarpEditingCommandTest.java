package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpEdit;
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
