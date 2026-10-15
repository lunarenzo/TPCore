package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.gui.WarpBrowser;
import com.lunatech.tpcore.module.warp.model.WarpBrowseQuery;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
        return commands(service, WarpConfig::createDefault, null);
    }
    private CommandDispatcher<CommandSourceStack> commands(WarpService service, Supplier<WarpConfig> config, WarpBrowser browser) throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>(); Commands registrar = mock(Commands.class);
        doAnswer(invocation -> { LiteralCommandNode<CommandSourceStack> node = invocation.getArgument(0); dispatcher.getRoot().addChild(node); return Set.of(); }).when(registrar).register(any(), anyString(), anyCollection());
        var registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, config, browser);
        Method method = WarpCommandRegistry.class.getDeclaredMethod("registerBrowseCommands", Commands.class); method.setAccessible(true); method.invoke(registry, registrar);
        return dispatcher;
    }

    private void collectClicks(Component component, List<ClickEvent> clicks) {
        if (component.clickEvent() != null) clicks.add(component.clickEvent());
        for (Component child : component.children()) collectClicks(child, clicks);
    }

    @Test void chatTeleportClicksContainEachActualWarpName() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true);
        when(service.getAllWarps()).thenReturn(List.of(warp("testwarp", "general"), warp("market", "shops")));
        CommandSender sender = mock(CommandSender.class); when(sender.hasPermission(Permissions.WARP_LIST)).thenReturn(true);
        List<ClickEvent> clicks = new ArrayList<>();
        doAnswer(invocation -> { collectClicks(invocation.getArgument(0), clicks); return null; }).when(sender).sendMessage(any(Component.class));
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(sender);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            commands(service).execute("warps", source);
        }
        assertTrue(clicks.contains(ClickEvent.runCommand("/warp testwarp")));
        assertTrue(clicks.contains(ClickEvent.runCommand("/warp market")));
        assertFalse(clicks.contains(ClickEvent.runCommand("/warp <name>")));
    }

    @Test void commandPlaceholdersResolveAfterParsingWithoutInjectingFormatting() throws Exception {
        var registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> null, WarpConfig::createDefault, null);
        Method method = WarpCommandRegistry.class.getDeclaredMethod("resolveWarpClickCommands", Component.class, String.class);
        method.setAccessible(true);
        Component original = MiniMessage.miniMessage().deserialize("<click:run_command:'/warp <name>'><hover:show_text:'Safe tooltip'><gold>[Teleport]</gold></hover></click> <click:suggest_command:'/warp <name>'>[Suggest]</click> <click:open_url:'https://example.org/<name>'>[Site]</click>");
        Component resolved = (Component) method.invoke(registry, original, "\"odd <red>name\"");
        List<ClickEvent> clicks = new ArrayList<>(); collectClicks(resolved, clicks);
        assertTrue(clicks.contains(ClickEvent.runCommand("/warp \"odd <red>name\"")));
        assertTrue(clicks.contains(ClickEvent.suggestCommand("/warp \"odd <red>name\"")));
        assertTrue(clicks.contains(ClickEvent.openUrl("https://example.org/<name>")));
        assertTrue(resolved.toString().contains("Safe tooltip"));
        assertTrue(original.toString().contains("/warp <name>"));
    }

    @Test void reloadSwitchesDefaultPlayerBrowsingAndFiltersWithoutReregisteringCommands() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true);
        when(service.getAllWarps()).thenReturn(List.of(warp("hub", "general")));
        AtomicReference<WarpConfig> config = new AtomicReference<>(WarpConfig.createDefault());
        WarpBrowser browser = mock(WarpBrowser.class);
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(Permissions.WARP_LIST)).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(player);
        try (var bukkit = mockStatic(Bukkit.class)) {
            var dispatcher = commands(service, config::get, browser);
            dispatcher.execute("warps", source); verifyNoInteractions(browser);
            config.set(WarpBrowserTest.settings(true, 16));
            dispatcher.execute("warps", source);
            verify(browser).open(player, new WarpBrowseQuery(null, null, 1, false));
            dispatcher.execute("warps category general search hub 2", source);
            verify(browser).open(player, new WarpBrowseQuery("general", "hub", 2, false));
            dispatcher.execute("warps categories 2", source);
            verify(browser).open(player, new WarpBrowseQuery(null, null, 2, true));
            clearInvocations(browser); config.set(WarpConfig.createDefault());
            dispatcher.execute("warps", source); verifyNoInteractions(browser);
        }
    }

    @Test void enabledInventoryBrowsingKeepsConsoleListsInChat() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true);
        when(service.getAllWarps()).thenReturn(List.of(warp("hub", "general")));
        CommandSender console = mock(CommandSender.class); when(console.hasPermission(Permissions.WARP_LIST)).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(console);
        WarpBrowser browser = mock(WarpBrowser.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
            commands(service, () -> WarpBrowserTest.settings(true, 16), browser).execute("warps", source);
        }
        verifyNoInteractions(browser); verify(console, atLeastOnce()).sendMessage(any(Component.class));
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
