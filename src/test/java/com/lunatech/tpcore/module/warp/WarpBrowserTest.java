package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpBrowseConfig;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.gui.WarpBrowser;
import com.lunatech.tpcore.module.warp.gui.WarpBrowserHolder;
import com.lunatech.tpcore.module.warp.listener.WarpBrowserListener;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpBrowseQuery;
import com.lunatech.tpcore.module.warp.service.WarpService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpBrowserTest {
    private static WarpConfig settings(boolean enabled, int capacity) {
        return settings(enabled, capacity, Map.of());
    }
    private static WarpConfig settings(boolean enabled, int capacity, Map<String, WarpBrowseConfig.Presentation> presentations) {
        WarpConfig d = WarpConfig.createDefault(); WarpBrowseConfig b = d.browsing();
        var browsing = new WarpBrowseConfig(enabled, b.inventoryRows(), b.maxSearchLength(), b.maxDescriptionLength(), b.maxPresentationEntries(),
            b.defaultIcon(), b.categoryIcon(), b.previousIcon(), b.nextIcon(), b.refreshIcon(), b.closeIcon(), presentations, b.messages());
        return new WarpConfig(d.enabled(), d.warmupSeconds(), d.cooldownSeconds(), d.cancelOnMove(), d.cancelOnDamage(), d.defaultPermissionGated(),
            d.defaultCategory(), d.warpsPerPage(), d.maxWarps(), d.economyEnabled(), d.warpCost(), d.setWarpCost(), d.chargeTiming(), d.refundOnCancel(),
            d.safetyChecks(), d.storage(), d.messages(), d.maxConcurrentOperations(), d.maxCachedWarps(), capacity, d.storageQueueCapacity(),
            d.operationTimeoutSeconds(), d.maxStorageBytes(), d.editMessages(), d.control(), d.controlMessages(), browsing);
    }
    private static Warp warp(boolean closed, boolean hidden, boolean restricted) {
        return new Warp("hub", null, "world", 0, 64, 0, 0, 0, null, "general", null, restricted, 0, closed, "Unsafe bridge", hidden);
    }

    private static final class Harness implements AutoCloseable {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final WarpService service = mock(WarpService.class);
        final AtomicReference<WarpConfig> config = new AtomicReference<>(settings(true, 1));
        final AtomicReference<Warp> current = new AtomicReference<>(warp(false, false, false));
        final List<WarpBrowserHolder> holders = new ArrayList<>();
        final List<Consumer<ScheduledTask>> tasks = new ArrayList<>();
        final List<ScheduledTask> handles = new ArrayList<>();
        final List<String> teleports = new ArrayList<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<Material> materials = mockStatic(Material.class);
        final MockedConstruction<ItemStack> items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(mock(ItemMeta.class)));
        final WarpBrowser browser;
        Harness() {
            when(player.getUniqueId()).thenReturn(id); when(player.hasPermission(anyString())).thenReturn(true);
            when(plugin.isEnabled()).thenReturn(true); when(service.isReady()).thenReturn(true);
            when(service.getAllWarps()).thenAnswer(ignored -> current.get() == null ? List.of() : List.of(current.get()));
            when(service.getWarp(anyString())).thenAnswer(invocation -> Optional.ofNullable(current.get()).filter(warp -> warp.name().equalsIgnoreCase(invocation.getArgument(0))));
            EntityScheduler scheduler = mock(EntityScheduler.class); when(player.getScheduler()).thenReturn(scheduler);
            doAnswer(invocation -> { tasks.add(invocation.getArgument(1)); ScheduledTask task = mock(ScheduledTask.class); handles.add(task); return task; }).when(scheduler).run(any(), any(), any());
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class))).thenAnswer(invocation -> {
                WarpBrowserHolder holder = invocation.getArgument(0); Inventory inventory = mock(Inventory.class);
                holders.add(holder); when(inventory.getHolder()).thenReturn(holder); when(inventory.getViewers()).thenReturn(List.of(player)); return inventory;
            });
            browser = new WarpBrowser(plugin, () -> service, config::get, (player, name) -> teleports.add(name));
        }
        WarpBrowserHolder open() { browser.open(player, new WarpBrowseQuery(null, null, 1, false)); return holders.getLast(); }
        void run(int index) { tasks.get(index).accept(handles.get(index)); }
        @Override public void close() { browser.invalidate(); items.close(); materials.close(); bukkit.close(); }
    }

    @Test void closureBetweenClickAndDeferredActionPreventsTeleportAndRefreshes() {
        try (Harness h = new Harness()) {
            var holder = h.open(); h.browser.click(h.player, holder, 0); assertTrue(h.teleports.isEmpty());
            h.current.set(warp(true, false, false)); h.run(0);
            assertTrue(h.teleports.isEmpty()); assertEquals(2, h.holders.size());
            verify(h.player).sendMessage(argThat((Component text) -> text.toString().contains("Unsafe bridge")));
        }
    }

    @Test void unlistingDeletionAndPermissionRevocationEachRejectOldEntries() {
        for (int scenario = 0; scenario < 3; scenario++) {
            try (Harness h = new Harness()) {
                var holder = h.open(); h.browser.click(h.player, holder, 0);
                if (scenario == 0) h.current.set(warp(false, true, false));
                else if (scenario == 1) h.current.set(null);
                else {
                    h.current.set(warp(false, false, true));
                    when(h.player.hasPermission("tpcore.warp.hub")).thenReturn(false); when(h.player.hasPermission(Permissions.WARP_ADMIN)).thenReturn(false);
                }
                h.run(0); assertTrue(h.teleports.isEmpty()); assertEquals(2, h.holders.size());
            }
        }
    }

    @Test void validSelectionIsDeferredAndRapidClicksQueueOnlyOneAction() {
        try (Harness h = new Harness()) {
            var holder = h.open(); h.browser.click(h.player, holder, 0); h.browser.click(h.player, holder, 0);
            assertEquals(1, h.tasks.size()); h.run(0); assertEquals(List.of("hub"), h.teleports); verify(h.player).closeInventory();
        }
    }

    @Test void revokedBaseUsePermissionAndModuleDisableCannotAuthorizeTeleport() {
        try (Harness h = new Harness()) {
            var holder = h.open(); h.browser.click(h.player, holder, 0);
            when(h.player.hasPermission(Permissions.WARP_USE)).thenReturn(false); h.run(0); assertTrue(h.teleports.isEmpty());
            var newHolder = h.holders.getLast(); h.browser.click(h.player, newHolder, 0);
            h.config.set(settings(false, 1)); h.run(1); assertTrue(h.teleports.isEmpty()); verify(h.player).closeInventory();
        }
    }

    @Test void closeQuitAndInvalidationRemoveQueuedWorkWithoutClosingReplacementViews() {
        try (Harness h = new Harness()) {
            var holder = h.open(); h.browser.click(h.player, holder, 0); h.browser.quit(h.id);
            verify(h.handles.getFirst()).cancel(); h.run(0); assertTrue(h.teleports.isEmpty());
            holder = h.open(); h.browser.invalidate();
            h.browser.forget(h.id, holder.token()); // Close event from another inventory replacing this view.
            h.run(1); verify(h.player, never()).closeInventory();
        }
    }

    @Test void clickDragAndBottomInventoryTransfersAreCanceled() {
        try (Harness h = new Harness()) {
            var holder = h.open(); var listener = new WarpBrowserListener(h.browser);
            InventoryClickEvent click = mock(InventoryClickEvent.class);
            when(click.getInventory()).thenReturn(holder.getInventory()); when(click.getWhoClicked()).thenReturn(h.player); when(click.getRawSlot()).thenReturn(54);
            when(click.getClick()).thenReturn(ClickType.LEFT);
            listener.click(click); verify(click).setCancelled(true); assertTrue(h.tasks.isEmpty());
            when(click.getRawSlot()).thenReturn(0); when(click.getClick()).thenReturn(ClickType.NUMBER_KEY);
            listener.click(click); assertTrue(h.tasks.isEmpty()); assertTrue(h.teleports.isEmpty());
            when(click.getClick()).thenReturn(ClickType.LEFT); when(click.isCancelled()).thenReturn(true);
            listener.click(click); assertTrue(h.tasks.isEmpty()); assertTrue(h.teleports.isEmpty());
            InventoryDragEvent drag = mock(InventoryDragEvent.class); when(drag.getInventory()).thenReturn(holder.getInventory());
            listener.drag(drag); verify(drag).setCancelled(true);
        }
    }

    @Test void menuAdmissionIsBoundedAndDisabledMenusAllocateNothing() {
        try (Harness h = new Harness()) {
            h.open(); Player second = mock(Player.class); when(second.getUniqueId()).thenReturn(UUID.randomUUID()); when(second.hasPermission(anyString())).thenReturn(true);
            h.browser.open(second, new WarpBrowseQuery(null, null, 1, false)); assertEquals(1, h.holders.size());
            when(h.player.getScheduler().run(any(), any(), any())).thenReturn(null);
            h.browser.click(h.player, h.holders.getFirst(), 0);
            h.browser.open(second, new WarpBrowseQuery(null, null, 1, false)); assertEquals(2, h.holders.size());
            h.config.set(settings(false, 1)); h.browser.open(h.player, new WarpBrowseQuery(null, null, 1, false)); assertEquals(2, h.holders.size());
        }
    }

    @Test void presentationDescriptionsRemainLiteralAndConfiguredIconsAreResolved() {
        try (Harness h = new Harness()) {
            h.config.set(settings(true, 1, Map.of("HUB", new WarpBrowseConfig.Presentation("UNKNOWN_ICON", "<red>Arrival point</red>"))));
            h.open();
            ItemMeta meta = h.items.constructed().getFirst().getItemMeta();
            verify(meta).displayName(argThat((Component text) -> text.toString().contains("hub")));
            verify(meta).lore(argThat(lines -> lines.getFirst().toString().contains("<red>Arrival point</red>")));
            h.materials.verify(() -> Material.matchMaterial("UNKNOWN_ICON"));
        }
    }
}
