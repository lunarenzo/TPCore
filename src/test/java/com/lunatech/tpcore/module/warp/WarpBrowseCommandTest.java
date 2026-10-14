package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpBrowseCommandTest {
    private Warp warp(String name, String category) { return new Warp(name, null, "world", 0, 64, 0, 0, 0, null, category, null, false, 0); }
    private CommandDispatcher<CommandSourceStack> commands(WarpService service) throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>(); Commands registrar = mock(Commands.class);
        doAnswer(invocation -> { LiteralCommandNode<CommandSourceStack> node = invocation.getArgument(0); dispatcher.getRoot().addChild(node); return Set.of(); }).when(registrar).register(any(), anyString(), anyCollection());
        var registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, WarpConfig::createDefault, null);
        Method method = WarpCommandRegistry.class.getDeclaredMethod("registerBrowseCommands", Commands.class); method.setAccessible(true); method.invoke(registry, registrar);
        return dispatcher;
    }

    @Test void combinedFiltersAndNextPageCommandRetainTheQueryAndSortResults() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true);
        List<Warp> warps = new ArrayList<>(); for (int i = 9; i >= 0; i--) warps.add(warp("hub" + i, "market")); warps.add(warp("hubElsewhere", "other"));
        when(service.getAllWarps()).thenReturn(warps);
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission(Permissions.WARP_LIST)).thenReturn(true);
        List<String> messages = new ArrayList<>(); doAnswer(invocation -> { messages.add(((Component) invocation.getArgument(0)).toString()); return null; }).when(sender).sendMessage(any(Component.class));
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class); doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            var commands = commands(service); commands.execute("warps category market search HUB", source);
            String first = String.join("", messages); assertTrue(first.indexOf("hub0") < first.indexOf("hub7")); assertFalse(first.contains("hub8")); assertFalse(first.contains("hubElsewhere"));
            assertTrue(first.contains("/warps category market search hub 2"));
            assertTrue(first.contains("/warps categories")); assertTrue(first.contains("suggest_command")); assertTrue(first.contains("/warps category market search "));
            messages.clear(); commands.execute("warps category market search hub 2", source);
            String last = String.join("", messages); assertTrue(last.contains("hub8")); assertTrue(last.contains("hub9")); assertFalse(last.contains("hub0")); assertTrue(last.contains("/warps category market search hub 1"));
            commands.execute("warps 999999999999999999999999999", source); // Saturates and clamps; never throws integer overflow.
        }
    }

    @Test void categoryDiscoveryHidesUnlistedAndDeniedOnlyCategories() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true);
        Warp hidden = new Warp("secret", null, "world", 0, 64, 0, 0, 0, null, "hidden", null, false, 0, false, "", true);
        Warp denied = new Warp("staff", null, "world", 0, 64, 0, 0, 0, null, "restricted", null, true, 0);
        when(service.getAllWarps()).thenReturn(List.of(hidden, denied, warp("hub", "general")));
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.hasPermission(Permissions.WARP_LIST)).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(player);
        var dispatcher = commands(service); var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("warps category ", source)).join();
        assertEquals(List.of("general"), suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList());
        assertThrows(Exception.class, () -> dispatcher.execute("warps search", source));
    }
}
