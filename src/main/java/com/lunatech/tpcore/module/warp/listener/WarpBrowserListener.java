package com.lunatech.tpcore.module.warp.listener;

import com.lunatech.tpcore.module.warp.gui.WarpBrowser;
import com.lunatech.tpcore.module.warp.gui.WarpBrowserHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class WarpBrowserListener implements Listener {
    private final WarpBrowser browser;
    public WarpBrowserListener(WarpBrowser browser) { this.browser = browser; }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WarpBrowserHolder holder)) return;
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true); // Also blocks shift-transfer, number keys, offhand swaps and double-click collection.
        if (alreadyCancelled) return; // Respect another plugin's veto while still protecting the menu contents.
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= holder.contentSize() + 9) return;
        if (event.getWhoClicked() instanceof Player player) browser.click(player, holder, event.getRawSlot());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WarpBrowserHolder) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof WarpBrowserHolder holder) browser.forget(event.getPlayer().getUniqueId(), holder.token());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void quit(PlayerQuitEvent event) { browser.quit(event.getPlayer().getUniqueId()); }
}
