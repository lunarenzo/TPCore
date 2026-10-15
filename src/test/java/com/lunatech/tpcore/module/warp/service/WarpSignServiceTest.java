package com.lunatech.tpcore.module.warp.service;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpSignConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.command.WarpSignCommandRegistry;
import com.lunatech.tpcore.module.warp.listener.WarpSignListener;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpSignBinding;
import com.lunatech.tpcore.module.warp.service.WarpSignService.Mode;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpSignServiceTest {
    private static final class Fixture {
        final Player player = mock(Player.class);
        final WarpService warps = mock(WarpService.class);
        final Block block = mock(Block.class);
        final Sign sign = mock(Sign.class);
        final SignSide front = mock(SignSide.class), back = mock(SignSide.class);
        final UUID worldId = UUID.randomUUID(), playerId = UUID.randomUUID();
        final Map<NamespacedKey, String> data = new HashMap<>();
        final List<String> teleports = new ArrayList<>();
        final WarpSignService service;
        final WarpSignListener listener;
        Warp current = new Warp("hub", worldId, "world", 0, 64, 0, 0, 0, playerId, "general", null, false, 1234);
        Fixture() { this(WarpConfig.createDefault()); }
        Fixture(WarpConfig settings) {
            Plugin plugin = mock(Plugin.class); when(plugin.getName()).thenReturn("TPCore");
            World world = mock(World.class); when(world.getUID()).thenReturn(worldId);
            when(block.getWorld()).thenReturn(world); when(block.getX()).thenReturn(-17); when(block.getY()).thenReturn(70); when(block.getZ()).thenReturn(-5);
            when(block.getType()).thenReturn(Material.OAK_SIGN); when(block.getState()).thenReturn(sign);
            when(sign.getSide(Side.FRONT)).thenReturn(front); when(sign.getSide(Side.BACK)).thenReturn(back);
            when(sign.getInteractableSideFor(player)).thenReturn(Side.FRONT); when(sign.update(false, false)).thenReturn(true);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class); when(sign.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.has(any(NamespacedKey.class))).thenAnswer(call -> data.containsKey(call.getArgument(0)));
            when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> data.get(call.getArgument(0)));
            doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
            doAnswer(call -> { data.remove(call.getArgument(0)); return null; }).when(pdc).remove(any(NamespacedKey.class));
            when(player.getUniqueId()).thenReturn(playerId); when(player.hasPermission(anyString())).thenReturn(true);
            when(warps.isReady()).thenReturn(true); when(warps.getWarp(anyString())).thenAnswer(call -> Optional.ofNullable(current).filter(w -> w.name().equalsIgnoreCase(call.getArgument(0))));
            service = new WarpSignService(plugin, () -> settings, () -> warps, (who, name) -> teleports.add(name), material -> material == Material.OAK_SIGN);
            listener = new WarpSignListener(service, () -> settings);
        }
        PlayerInteractEvent click() {
            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getPlayer()).thenReturn(player); when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND); when(event.getClickedBlock()).thenReturn(block);
            when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
            return event;
        }
        void create() { service.prepare(player, Mode.CREATE, "hub"); service.interact(click()); }
    }

    @Test void creationPersistsPositionIdentityAndOnlyChangesTheSelectedFace() {
        Fixture f = new Fixture(); f.create();
        assertEquals(1, f.data.size());
        WarpSignBinding binding = WarpSignBinding.decode(f.data.values().iterator().next()).orElseThrow();
        assertTrue(binding.at(f.worldId, -17, 70, -5)); assertTrue(binding.matches(f.current));
        verify(f.front).line(eq(1), argThat((Component text) -> text.toString().contains("hub")));
        verifyNoInteractions(f.back); verify(f.sign).update(false, false); assertTrue(f.teleports.isEmpty());
        f.service.interact(f.click()); assertEquals(List.of("hub"), f.teleports);
        f.service.interact(f.click()); assertEquals(1, f.teleports.size());
    }

    @Test void offhandAndProtectionDenialCannotBindOrTeleport() {
        Fixture f = new Fixture(); f.service.prepare(f.player, Mode.CREATE, "hub");
        PlayerInteractEvent event = f.click(); when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND); f.service.interact(event);
        assertTrue(f.data.isEmpty());
        event = f.click(); when(event.useInteractedBlock()).thenReturn(Event.Result.DENY); f.service.interact(event);
        assertTrue(f.data.isEmpty()); f.service.interact(f.click()); assertEquals(1, f.data.size());
    }

    @Test void permissionsAreRecheckedAfterSelectionAndAtEveryUse() {
        Fixture f = new Fixture(); f.service.prepare(f.player, Mode.CREATE, "hub");
        when(f.player.hasPermission(Permissions.WARP_ADMIN)).thenReturn(false);
        when(f.player.hasPermission(Permissions.WARP_SIGN_CREATE)).thenReturn(false);
        f.service.interact(f.click()); assertTrue(f.data.isEmpty());
        when(f.player.hasPermission(Permissions.WARP_SIGN_CREATE)).thenReturn(true); f.create();
        when(f.player.hasPermission(Permissions.WARP_SIGN_USE)).thenReturn(false);
        f.service.interact(f.click()); assertTrue(f.teleports.isEmpty());
    }

    @Test void deletedRenamedReplacedAndMovedBindingsCannotTeleport() {
        for (int scenario = 0; scenario < 4; scenario++) {
            Fixture f = new Fixture(); f.create();
            if (scenario == 0) f.current = null;
            else if (scenario == 1) f.current = new Warp("renamed", f.worldId, "world", 0, 64, 0, 0, 0, f.playerId, "general", null, false, 1234);
            else if (scenario == 2) f.current = new Warp("hub", f.worldId, "world", 0, 64, 0, 0, 0, f.playerId, "general", null, false, 1235);
            else when(f.block.getX()).thenReturn(-18);
            f.service.interact(f.click()); assertTrue(f.teleports.isEmpty());
        }
    }

    @Test void metadataNotVisibleTextAuthorizesSignsAndMalformedDataFailsClosed() {
        Fixture f = new Fixture(); f.service.interact(f.click()); assertTrue(f.teleports.isEmpty());
        verify(f.front, never()).line(anyInt()); // Writing [Warp] on an ordinary sign does not matter.
        f.create(); f.data.replaceAll((key, value) -> "invalid|metadata");
        f.service.interact(f.click()); assertTrue(f.teleports.isEmpty());
        f.service.prepare(f.player, Mode.REMOVE, null); f.service.interact(f.click()); assertTrue(f.data.isEmpty());
    }

    @Test void frontAndBackBindingsAreIndependentAndRemovalPreservesTheOtherFace() {
        Fixture f = new Fixture(); f.create();
        when(f.sign.getInteractableSideFor(f.player)).thenReturn(Side.BACK); f.create(); assertEquals(2, f.data.size());
        f.service.prepare(f.player, Mode.REMOVE, null); f.service.interact(f.click()); assertEquals(1, f.data.size());
        assertTrue(f.service.managed(f.sign, Side.FRONT)); assertFalse(f.service.managed(f.sign, Side.BACK));
        when(f.sign.getInteractableSideFor(f.player)).thenReturn(Side.FRONT); f.service.interact(f.click()); assertEquals(List.of("hub"), f.teleports);
    }

    @Test void quitReloadAndInvalidNewCommandsClearPendingActions() {
        Fixture f = new Fixture(); f.service.prepare(f.player, Mode.CREATE, "hub"); f.service.forget(f.playerId);
        f.service.interact(f.click()); assertTrue(f.data.isEmpty());
        f.service.prepare(f.player, Mode.CREATE, "hub"); f.service.invalidate(); f.service.interact(f.click()); assertTrue(f.data.isEmpty());
        f.service.prepare(f.player, Mode.CREATE, "hub"); f.service.prepare(f.player, Mode.CREATE, "missing"); f.service.interact(f.click()); assertTrue(f.data.isEmpty());
    }

    @Test void managedEditsAndUnauthorizedDirectBreaksAreBlocked() {
        Fixture f = new Fixture(); f.create();
        SignChangeEvent edit = mock(SignChangeEvent.class); when(edit.getBlock()).thenReturn(f.block); when(edit.getSide()).thenReturn(Side.FRONT); when(edit.getPlayer()).thenReturn(f.player);
        f.listener.edit(edit); verify(edit).setCancelled(true);
        when(f.player.hasPermission(Permissions.WARP_ADMIN)).thenReturn(false); when(f.player.hasPermission(Permissions.WARP_SIGN_BREAK)).thenReturn(false);
        BlockBreakEvent breaking = mock(BlockBreakEvent.class); when(breaking.getBlock()).thenReturn(f.block); when(breaking.getPlayer()).thenReturn(f.player);
        f.listener.breakSign(breaking); verify(breaking).setCancelled(true);
    }

    @Test void creationDoesNotActOnAReplacementWarpAfterSelection() {
        Fixture f = new Fixture(); f.service.prepare(f.player, Mode.CREATE, "hub");
        f.current = new Warp("hub", f.worldId, "world", 0, 64, 0, 0, 0, f.playerId, "general", null, false, 5678);
        f.service.interact(f.click()); assertTrue(f.data.isEmpty());
    }

    @Test void commandTreeRequiresManagementPermissionsAndDoesNotDispatchArbitraryCommands() throws Exception {
        Fixture f = new Fixture(); Commands registrar = mock(Commands.class); CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        doAnswer(call -> { LiteralCommandNode<CommandSourceStack> node = call.getArgument(0); dispatcher.getRoot().addChild(node); return Set.of(); }).when(registrar).register(any(LiteralCommandNode.class));
        new WarpSignCommandRegistry(f.service).register(registrar);
        CommandSourceStack source = mock(CommandSourceStack.class); when(source.getSender()).thenReturn(f.player);
        dispatcher.execute("warpsign create hub", source); f.service.interact(f.click()); assertEquals(1, f.data.size());
        when(f.player.hasPermission(anyString())).thenReturn(false);
        assertThrows(Exception.class, () -> dispatcher.execute("warpsign create hub", source));
        assertThrows(Exception.class, () -> dispatcher.execute("warpsign run op me", source));
    }

    @Test void bindingCodecRejectsCorruptAndOversizedDataAndRoundTripsNegativeCoordinates() {
        Fixture f = new Fixture(); f.create(); String raw = f.data.values().iterator().next();
        assertEquals(raw, WarpSignBinding.decode(raw).orElseThrow().encode());
        for (String invalid : List.of("", "x".repeat(257), raw.replace("1|", "2|"), "1|bad|0|0|0|hub|0|")) assertTrue(WarpSignBinding.decode(invalid).isEmpty());
        assertTrue(WarpSignBinding.decode(null).isEmpty());
    }

    @Test void fullSelectionCapacityRejectsNewActorsAndQuitReleasesCapacity() {
        WarpConfig settings = spy(WarpConfig.createDefault());
        when(settings.maxPlayerStateEntries()).thenReturn(1);
        Fixture f = new Fixture(settings);
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID()); when(other.hasPermission(anyString())).thenReturn(true);
        f.service.prepare(f.player, Mode.CREATE, "hub");
        f.service.prepare(other, Mode.CREATE, "hub");
        PlayerInteractEvent otherClick = f.click(); when(otherClick.getPlayer()).thenReturn(other);
        f.service.interact(otherClick); assertTrue(f.data.isEmpty());
        f.service.forget(f.playerId);
        f.service.prepare(other, Mode.CREATE, "hub");
        when(f.sign.getInteractableSideFor(other)).thenReturn(Side.FRONT);
        f.service.interact(otherClick); assertEquals(1, f.data.size());
    }

    @Test void expiredRemovalDoesNotBecomeATeleportClick() throws InterruptedException {
        WarpConfig settings = spy(WarpConfig.createDefault());
        WarpSignConfig defaults = WarpSignConfig.createDefault();
        when(settings.signs()).thenReturn(new WarpSignConfig(true, 1, 750, true, defaults.lines(), defaults.messages()));
        Fixture f = new Fixture(settings); f.create();
        f.service.prepare(f.player, Mode.REMOVE, null);
        Thread.sleep(1100);
        PlayerInteractEvent click = f.click(); f.service.interact(click);
        assertEquals(1, f.data.size()); assertTrue(f.teleports.isEmpty());
        verify(click).setUseInteractedBlock(Event.Result.DENY); verify(click).setUseItemInHand(Event.Result.DENY);
    }
}
