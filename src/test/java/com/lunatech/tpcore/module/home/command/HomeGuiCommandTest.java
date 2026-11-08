package com.lunatech.tpcore.module.home.command;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.config.model.HomeGuiConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.gui.HomeMenus;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeGuiCommandTest {
    @Test void guiRoutesQuotedNamesPermissionsAndDisabledFallback() throws Exception {
        var config = new AtomicReference<>(HomeConfig.createDefault());
        var disabled = mock(HomeConfig.class);
        var disabledGui = mock(HomeGuiConfig.class);
        when(disabled.gui()).thenReturn(disabledGui);
        when(disabled.enabled()).thenReturn(true);
        when(disabled.defaultHomeName()).thenReturn("home");
        when(disabled.homeLimits()).thenReturn(config.get().homeLimits());
        when(disabled.runtime()).thenReturn(config.get().runtime());
        var menus = mock(HomeMenus.class);
        var service = mock(DefaultHomeService.class);
        var player = mock(Player.class);
        var plugin = mock(JavaPlugin.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getScheduler()).thenReturn(mock(EntityScheduler.class));
        var permissions = new HashSet<>(Set.of(Permissions.HOME_LIST, Permissions.HOME_SET));
        when(player.hasPermission(anyString())).thenAnswer(call -> permissions.contains(call.getArgument(0)));
        when(service.teleport(eq(player), eq(id), anyString(), eq(Permissions.HOME_USE), eq(false)))
            .thenReturn(CompletableFuture.completedFuture(HomeResultStatus.SUCCESS));
        when(service.list(eq(id), anyInt(), anyLong())).thenReturn(CompletableFuture.completedFuture(List.of()));

        var source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var registrar = mock(Commands.class);
        doAnswer(call -> {
            dispatcher.getRoot().addChild(call.<LiteralCommandNode<CommandSourceStack>>getArgument(0));
            return Set.of();
        }).when(registrar).register(any(), anyString(), anyCollection());
        new HomeCommandRegistry(plugin, () -> service, config::get, menus).register(registrar);

        dispatcher.execute("home gui", source);
        dispatcher.execute("homes", source);
        dispatcher.execute("home", source);
        dispatcher.execute("home gui set token base", source);
        dispatcher.execute("home gui cancel token", source);
        verify(menus).main(player);
        verify(menus).homes(player);
        verify(menus).bareHome(player);
        verify(menus).submit(player, "token", "base");
        verify(menus).submit(player, "token", null);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("home \"gui\"", source));
        permissions.add(Permissions.HOME_USE);
        dispatcher.execute("home \"gui\"", source);
        verify(service).teleport(player, id, "gui", Permissions.HOME_USE, false);
        dispatcher.execute("homes 2", source);
        verify(service).list(id, 11, 10);
        clearInvocations(menus);
        config.set(disabled);
        dispatcher.execute("home", source);
        dispatcher.execute("homes", source);
        verifyNoInteractions(menus);
        verify(service).teleport(player, id, "home", Permissions.HOME_USE, false);
        verify(service).list(id, 11, 0);
    }
}
