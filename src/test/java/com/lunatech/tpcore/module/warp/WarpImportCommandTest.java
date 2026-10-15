package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.command.WarpCommandRegistry;
import com.lunatech.tpcore.module.warp.model.WarpImportPlan;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpImportCommandTest {
    private CommandDispatcher<CommandSourceStack> commands(WarpService service) throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>(); Commands registrar = mock(Commands.class);
        doAnswer(invocation -> { LiteralCommandNode<CommandSourceStack> node = invocation.getArgument(0); dispatcher.getRoot().addChild(node); return Set.of(); }).when(registrar).register(any(), anyString(), anyCollection());
        var registry = new WarpCommandRegistry(mock(JavaPlugin.class), () -> service, WarpConfig::createDefault, null);
        Method method = WarpCommandRegistry.class.getDeclaredMethod("registerImportCommands", Commands.class); method.setAccessible(true); method.invoke(registry, registrar); return dispatcher;
    }
    @Test void consoleCommandRequiresPermissionAndUsesExplicitPreviewPoliciesAndToken() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true);
        var plan = new WarpImportPlan(UUID.randomUUID(), "skip", Map.of(), Map.of(), List.of(), List.of(), 0, 0);
        when(service.previewEssentialsImport(any(), anyString(), anyMap())).thenReturn(CompletableFuture.completedFuture(plan));
        when(service.confirmEssentialsImport(any(), any(), anyMap())).thenReturn(CompletableFuture.completedFuture(new WarpImportPlan.Result(1, "backup")));
        CommandSender console = mock(CommandSender.class); CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(console);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class); doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global); bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            var commands = commands(service); assertThrows(Exception.class, () -> commands.execute("warpimport essentials preview", source));
            when(console.hasPermission(Permissions.WARP_IMPORT)).thenReturn(true);
            commands.execute("warpimport essentials preview", source); commands.execute("warpimport essentials preview replace", source); commands.execute("warpimport essentials preview rename", source);
            verify(service).previewEssentialsImport(new UUID(0, 0), "skip", Map.of()); verify(service).previewEssentialsImport(new UUID(0, 0), "replace", Map.of()); verify(service).previewEssentialsImport(new UUID(0, 0), "rename", Map.of());
            commands.execute("warpimport essentials confirm " + plan.token(), source); verify(service).confirmEssentialsImport(new UUID(0, 0), plan.token(), Map.of());
        }
    }
    @Test void queuedPlayerCommandRechecksPermissionOnOwningEntityThread() throws Exception {
        WarpService service = mock(WarpService.class); when(service.isReady()).thenReturn(true); UUID id = UUID.randomUUID();
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(id); when(player.hasPermission(Permissions.WARP_IMPORT)).thenReturn(true);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(player);
        List<Consumer<ScheduledTask>> tasks = new ArrayList<>(); EntityScheduler entity = mock(EntityScheduler.class); when(player.getScheduler()).thenReturn(entity);
        when(entity.run(any(), any(), any())).thenAnswer(invocation -> { tasks.add(invocation.getArgument(1)); return mock(ScheduledTask.class); });
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class); doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; }).when(global).execute(any(), any());
        World world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.getName()).thenReturn("world"); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global); bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world)); bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            commands(service).execute("warpimport essentials preview", source);
            verify(service, never()).previewEssentialsImport(any(), anyString(), anyMap()); assertEquals(1, tasks.size());
            when(player.hasPermission(Permissions.WARP_IMPORT)).thenReturn(false); tasks.getFirst().accept(mock(ScheduledTask.class));
            verify(service, never()).previewEssentialsImport(any(), anyString(), anyMap());
        }
    }
}
