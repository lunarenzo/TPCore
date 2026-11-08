package com.lunatech.tpcore.module.home.listener;

import com.lunatech.tpcore.module.home.gui.HomeMenuHolder;
import com.lunatech.tpcore.module.home.gui.HomeMenus;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class HomeMenuListener implements Listener {
    private final HomeMenus menus;

    public HomeMenuListener(HomeMenus menus) { this.menus = menus; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof HomeMenuHolder holder)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || !(event.getWhoClicked() instanceof Player player)) return;
        ClickType click = event.getClick();
        int slot = event.getRawSlot();
        if ((click == ClickType.LEFT || click == ClickType.RIGHT) && slot >= 0 && slot < event.getInventory().getSize()) {
            menus.click(player, holder, slot, click);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof HomeMenuHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        var holder = event.getInventory().getHolder(false);
        menus.opened(event.getPlayer().getUniqueId(), holder instanceof HomeMenuHolder menu ? menu : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        var holder = event.getInventory().getHolder(false);
        menus.closed(event.getPlayer().getUniqueId(), holder instanceof HomeMenuHolder menu ? menu : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { menus.quit(event.getPlayer().getUniqueId()); }
}
