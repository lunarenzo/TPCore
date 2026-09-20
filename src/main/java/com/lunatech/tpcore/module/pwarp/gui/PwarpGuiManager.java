package com.lunatech.tpcore.module.pwarp.gui;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manages zero-GC paginated chest GUIs for PlayerWarps (All Warps & My Warps).
 */
public final class PwarpGuiManager implements Listener {

    private static final int SLOTS_PER_PAGE = 45;
    private static final int GUI_SIZE = 54;

    private final JavaPlugin plugin;
    private final PwarpService pwarpService;
    private final MiniMessage miniMessage;

    public PwarpGuiManager(JavaPlugin plugin, PwarpService pwarpService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.pwarpService = Objects.requireNonNull(pwarpService, "pwarpService cannot be null");
        this.miniMessage = MiniMessage.miniMessage();
    }

    /**
     * Opens the public player warps GUI for the specified player at the given page.
     *
     * @param player Target player
     * @param page   0-indexed page number
     */
    public void openWarpsGui(Player player, int page) {
        List<Pwarp> allWarps = this.pwarpService.getPublicWarps();
        int totalPages = Math.max(1, (int) Math.ceil((double) allWarps.size() / SLOTS_PER_PAGE));
        int targetPage = Math.max(0, Math.min(page, totalPages - 1));

        Component title = this.miniMessage.deserialize(
            "<gradient:#FFAA00:#FF5500><bold>Player Warps</bold></gradient> <gray>(Page " + (targetPage + 1) + "/" + totalPages + ")</gray>"
        );

        PwarpInventoryHolder holder = new PwarpInventoryHolder(PwarpInventoryHolder.ViewType.ALL_WARPS, targetPage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
        holder.setInventory(inventory);

        int fromIndex = targetPage * SLOTS_PER_PAGE;
        int toIndex = Math.min(fromIndex + SLOTS_PER_PAGE, allWarps.size());
        List<Pwarp> pageWarps = (fromIndex < allWarps.size()) ? allWarps.subList(fromIndex, toIndex) : List.of();

        for (int i = 0; i < pageWarps.size(); i++) {
            Pwarp warp = pageWarps.get(i);
            inventory.setItem(i, createWarpItemStack(warp, false));
        }

        populateControlBar(inventory, holder.getViewType(), targetPage, totalPages, allWarps.size());

        player.getScheduler().run(this.plugin, task -> player.openInventory(inventory), null);
    }

    /**
     * Opens the owner's personal player warps GUI for managing their set warps.
     *
     * @param player Target player
     * @param page   0-indexed page number
     */
    public void openMyWarpsGui(Player player, int page) {
        List<Pwarp> myWarps = this.pwarpService.getPlayerWarps(player.getUniqueId());
        int totalPages = Math.max(1, (int) Math.ceil((double) myWarps.size() / SLOTS_PER_PAGE));
        int targetPage = Math.max(0, Math.min(page, totalPages - 1));

        Component title = this.miniMessage.deserialize(
            "<gradient:#FFAA00:#FF5500><bold>My Player Warps</bold></gradient> <gray>(Page " + (targetPage + 1) + "/" + totalPages + ")</gray>"
        );

        PwarpInventoryHolder holder = new PwarpInventoryHolder(PwarpInventoryHolder.ViewType.MY_WARPS, targetPage, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
        holder.setInventory(inventory);

        int fromIndex = targetPage * SLOTS_PER_PAGE;
        int toIndex = Math.min(fromIndex + SLOTS_PER_PAGE, myWarps.size());
        List<Pwarp> pageWarps = (fromIndex < myWarps.size()) ? myWarps.subList(fromIndex, toIndex) : List.of();

        for (int i = 0; i < pageWarps.size(); i++) {
            Pwarp warp = pageWarps.get(i);
            inventory.setItem(i, createWarpItemStack(warp, true));
        }

        populateControlBar(inventory, holder.getViewType(), targetPage, totalPages, myWarps.size());

        player.getScheduler().run(this.plugin, task -> player.openInventory(inventory), null);
    }

    private ItemStack createWarpItemStack(Pwarp warp, boolean isOwnerView) {
        Material material = Material.matchMaterial(warp.iconMaterial());
        if (material == null) {
            material = Material.OAK_SIGN;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(this.miniMessage.deserialize("<gold><bold>" + warp.name() + "</bold></gold>"));

            List<Component> lore = new ArrayList<>();
            lore.add(this.miniMessage.deserialize("<gray>Owner: </gray><yellow>" + warp.ownerName() + "</yellow>"));
            if (warp.description() != null && !warp.description().isBlank()) {
                lore.add(this.miniMessage.deserialize("<gray>Desc: </gray><white>" + warp.description() + "</white>"));
            }
            lore.add(this.miniMessage.deserialize("<gray>Visits: </gray><green>" + warp.visits() + "</green>"));
            lore.add(Component.empty());

            if (isOwnerView) {
                lore.add(this.miniMessage.deserialize("<yellow>Click to teleport</yellow>"));
                lore.add(this.miniMessage.deserialize("<red>Shift-Right-Click to DELETE</red>"));
            } else {
                lore.add(this.miniMessage.deserialize("<yellow>Click to teleport</yellow>"));
            }

            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void populateControlBar(Inventory inventory, PwarpInventoryHolder.ViewType viewType, int page, int totalPages, int totalWarps) {
        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, "<gray> </gray>");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // Slot 45: Previous Page
        if (page > 0) {
            inventory.setItem(45, createGuiItem(Material.ARROW, "<yellow><bold>← Previous Page</bold></yellow>"));
        }

        // Slot 48: View Switcher
        if (viewType == PwarpInventoryHolder.ViewType.ALL_WARPS) {
            inventory.setItem(48, createGuiItem(Material.NETHER_STAR, "<gold><bold>My Warps</bold></gold>", "<gray>Click to view your set warps</gray>"));
        } else {
            inventory.setItem(48, createGuiItem(Material.COMPASS, "<gold><bold>All Warps</bold></gold>", "<gray>Click to view all public warps</gray>"));
        }

        // Slot 49: Info / Counter
        inventory.setItem(49, createGuiItem(
            Material.BOOK,
            "<gradient:#FFAA00:#FF5500><bold>Page " + (page + 1) + " of " + totalPages + "</bold></gradient>",
            "<gray>Total Warps: </gray><yellow>" + totalWarps + "</yellow>"
        ));

        // Slot 50: Next Page
        if (page < totalPages - 1) {
            inventory.setItem(50, createGuiItem(Material.ARROW, "<yellow><bold>Next Page →</bold></yellow>"));
        }

        // Slot 52: Close
        inventory.setItem(52, createGuiItem(Material.BARRIER, "<red><bold>Close Menu</bold></red>"));
    }

    private ItemStack createGuiItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(this.miniMessage.deserialize(name));
            if (loreLines.length > 0) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(this.miniMessage.deserialize(line));
                }
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PwarpInventoryHolder holder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getSlot();

        if (slot >= 0 && slot < SLOTS_PER_PAGE) {
            int warpIndex = (holder.getPage() * SLOTS_PER_PAGE) + slot;

            if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                List<Pwarp> allWarps = this.pwarpService.getPublicWarps();
                if (warpIndex < allWarps.size()) {
                    Pwarp warp = allWarps.get(warpIndex);
                    player.getScheduler().run(this.plugin, task -> player.closeInventory(), null);
                    this.pwarpService.executeTeleport(player, warp.name());
                }
            } else if (holder.getViewType() == PwarpInventoryHolder.ViewType.MY_WARPS) {
                List<Pwarp> myWarps = this.pwarpService.getPlayerWarps(player.getUniqueId());
                if (warpIndex < myWarps.size()) {
                    Pwarp warp = myWarps.get(warpIndex);
                    if (event.isShiftClick() && event.isRightClick()) {
                        this.pwarpService.deleteWarp(player, warp.name());
                        openMyWarpsGui(player, holder.getPage());
                    } else {
                        player.getScheduler().run(this.plugin, task -> player.closeInventory(), null);
                        this.pwarpService.executeTeleport(player, warp.name());
                    }
                }
            }
            return;
        }

        switch (slot) {
            case 45 -> { // Previous Page
                if (holder.getPage() > 0) {
                    if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                        openWarpsGui(player, holder.getPage() - 1);
                    } else {
                        openMyWarpsGui(player, holder.getPage() - 1);
                    }
                }
            }
            case 48 -> { // Toggle View
                if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                    openMyWarpsGui(player, 0);
                } else {
                    openWarpsGui(player, 0);
                }
            }
            case 50 -> { // Next Page
                if (holder.getPage() < holder.getTotalPages() - 1) {
                    if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                        openWarpsGui(player, holder.getPage() + 1);
                    } else {
                        openMyWarpsGui(player, holder.getPage() + 1);
                    }
                }
            }
            case 52 -> player.getScheduler().run(this.plugin, task -> player.closeInventory(), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PwarpInventoryHolder) {
            event.setCancelled(true);
        }
    }
}
