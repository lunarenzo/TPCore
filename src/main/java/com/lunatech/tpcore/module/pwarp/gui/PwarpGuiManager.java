package com.lunatech.tpcore.module.pwarp.gui;

import com.lunatech.tpcore.module.pwarp.config.PwarpConfig;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.model.PwarpCategory;
import com.lunatech.tpcore.module.pwarp.model.PwarpSorting;
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
import java.util.function.Supplier;

/**
 * Manages zero-GC paginated chest GUIs for PlayerWarps (All Warps, Category Selector, My Warps & Rate Warp).
 */
public final class PwarpGuiManager implements Listener {

    private static final int SLOTS_PER_PAGE = 45;
    private static final int GUI_SIZE = 54;

    private final JavaPlugin plugin;
    private final PwarpService pwarpService;
    private final Supplier<PwarpConfig> configSupplier;
    private final MiniMessage miniMessage;

    public PwarpGuiManager(JavaPlugin plugin, PwarpService pwarpService, Supplier<PwarpConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.pwarpService = Objects.requireNonNull(pwarpService, "pwarpService cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void openWarpsGui(Player player, int page) {
        openWarpsGui(player, page, PwarpSorting.MOST_VISITED, "all");
    }

    public void openWarpsGui(Player player, int page, PwarpSorting sorting, String categoryFilter) {
        PwarpSorting activeSorting = (sorting != null) ? sorting : PwarpSorting.MOST_VISITED;
        String activeCategory = (categoryFilter != null && !categoryFilter.isBlank()) ? categoryFilter : "all";

        List<Pwarp> allWarps = this.pwarpService.getPublicWarps(activeSorting, activeCategory);
        int totalPages = Math.max(1, (int) Math.ceil((double) allWarps.size() / SLOTS_PER_PAGE));
        int targetPage = Math.max(0, Math.min(page, totalPages - 1));

        Component title = this.miniMessage.deserialize(
            "<gradient:#FFAA00:#FF5500><bold>Player Warps</bold></gradient> <gray>(" + activeCategory.toUpperCase() + " | Page " + (targetPage + 1) + "/" + totalPages + ")</gray>"
        );

        PwarpInventoryHolder holder = new PwarpInventoryHolder(
            PwarpInventoryHolder.ViewType.ALL_WARPS,
            targetPage,
            totalPages,
            activeSorting,
            activeCategory
        );
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
        holder.setInventory(inventory);

        int fromIndex = targetPage * SLOTS_PER_PAGE;
        int toIndex = Math.min(fromIndex + SLOTS_PER_PAGE, allWarps.size());
        List<Pwarp> pageWarps = (fromIndex < allWarps.size()) ? allWarps.subList(fromIndex, toIndex) : List.of();

        for (int i = 0; i < pageWarps.size(); i++) {
            Pwarp warp = pageWarps.get(i);
            inventory.setItem(i, createWarpItemStack(warp, false));
        }

        populateControlBar(inventory, holder, allWarps.size());

        player.getScheduler().run(this.plugin, task -> player.openInventory(inventory), null);
    }

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

        populateControlBar(inventory, holder, myWarps.size());

        player.getScheduler().run(this.plugin, task -> player.openInventory(inventory), null);
    }

    public void openCategoryGui(Player player) {
        List<PwarpCategory> categories = this.configSupplier.get().categories();
        Component title = this.miniMessage.deserialize(
            "<gradient:#FFAA00:#FF5500><bold>Select Warp Category</bold></gradient>"
        );

        PwarpInventoryHolder holder = new PwarpInventoryHolder(PwarpInventoryHolder.ViewType.CATEGORY_SELECT, 0, 1);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
        holder.setInventory(inventory);

        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, "<gray> </gray>");
        for (int i = 0; i < GUI_SIZE; i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(4, createGuiItem(
            Material.COMPASS,
            "<gold><bold>All Categories</bold></gold>",
            "<gray>Click to view warps from all categories</gray>"
        ));

        for (PwarpCategory cat : categories) {
            int slot = cat.slot();
            if (slot >= 0 && slot < GUI_SIZE && slot != 4) {
                Material mat = Material.matchMaterial(cat.iconMaterial());
                if (mat == null) mat = Material.CHEST;

                inventory.setItem(slot, createGuiItem(
                    mat,
                    "<yellow><bold>" + cat.displayName() + "</bold></yellow>",
                    "<gray>" + cat.description() + "</gray>",
                    "<yellow>Click to view warps in this category</yellow>"
                ));
            }
        }

        inventory.setItem(49, createGuiItem(Material.BARRIER, "<red><bold>Back to Warps</bold></red>"));

        player.getScheduler().run(this.plugin, task -> player.openInventory(inventory), null);
    }

    public void openRateWarpGui(Player player, Pwarp warp) {
        Objects.requireNonNull(warp, "warp cannot be null");
        Component title = this.miniMessage.deserialize(
            "<gradient:#FFAA00:#FF5500><bold>Rate Warp: " + warp.name() + "</bold></gradient>"
        );

        PwarpInventoryHolder holder = new PwarpInventoryHolder(PwarpInventoryHolder.ViewType.RATE_WARP, warp);
        Inventory inventory = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inventory);

        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, "<gray> </gray>");
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        int[] slots = {11, 12, 13, 14, 15};
        for (int i = 0; i < 5; i++) {
            int stars = i + 1;
            inventory.setItem(slots[i], createGuiItem(
                Material.NETHER_STAR,
                "<gold><bold>" + stars + " Star" + (stars > 1 ? "s" : "") + "</bold></gold>",
                "<gray>Click to give </gray><yellow>" + stars + " ★</yellow><gray> rating</gray>"
            ));
        }

        inventory.setItem(22, createGuiItem(Material.BARRIER, "<red><bold>Cancel</bold></red>"));

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
            lore.add(this.miniMessage.deserialize("<gray>Category: </gray><green>" + warp.category().toUpperCase() + "</green>"));
            lore.add(this.miniMessage.deserialize("<gray>Rating: </gray><gold>" + String.format("%.1f", warp.averageRating()) + " ★</gold> <gray>(" + warp.totalRatings() + " votes)</gray>"));
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
                lore.add(this.miniMessage.deserialize("<gold>Right-Click to Rate Warp ★</gold>"));
            }

            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void populateControlBar(Inventory inventory, PwarpInventoryHolder holder, int totalWarps) {
        ItemStack filler = createGuiItem(Material.GRAY_STAINED_GLASS_PANE, "<gray> </gray>");
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        int page = holder.getPage();
        int totalPages = holder.getTotalPages();

        if (page > 0) {
            inventory.setItem(45, createGuiItem(Material.ARROW, "<yellow><bold>← Previous Page</bold></yellow>"));
        }

        if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
            inventory.setItem(46, createGuiItem(
                Material.CHEST,
                "<gold><bold>Category: </bold><yellow>" + holder.getCategoryFilter().toUpperCase() + "</yellow></gold>",
                "<gray>Click to filter warps by category</gray>"
            ));

            inventory.setItem(47, createGuiItem(
                Material.HOPPER,
                "<gold><bold>Sort: </bold><yellow>" + holder.getSorting().getDisplayName() + "</yellow></gold>",
                "<gray>Click to cycle sorting criteria</gray>"
            ));

            inventory.setItem(48, createGuiItem(Material.NETHER_STAR, "<gold><bold>My Warps</bold></gold>", "<gray>Click to view your set warps</gray>"));
        } else {
            inventory.setItem(48, createGuiItem(Material.COMPASS, "<gold><bold>All Warps</bold></gold>", "<gray>Click to view all public warps</gray>"));
        }

        inventory.setItem(49, createGuiItem(
            Material.BOOK,
            "<gradient:#FFAA00:#FF5500><bold>Page " + (page + 1) + " of " + totalPages + "</bold></gradient>",
            "<gray>Total Warps: </gray><yellow>" + totalWarps + "</yellow>"
        ));

        if (page < totalPages - 1) {
            inventory.setItem(50, createGuiItem(Material.ARROW, "<yellow><bold>Next Page →</bold></yellow>"));
        }

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

        int rawSlot = event.getRawSlot();
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory() || rawSlot < 0) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (holder.getViewType() == PwarpInventoryHolder.ViewType.RATE_WARP) {
            handleRateWarpClick(player, holder.getTargetWarp(), rawSlot);
            return;
        }

        if (rawSlot >= GUI_SIZE) {
            return;
        }

        if (holder.getViewType() == PwarpInventoryHolder.ViewType.CATEGORY_SELECT) {
            handleCategoryClick(player, rawSlot);
            return;
        }

        if (rawSlot < SLOTS_PER_PAGE) {
            int warpIndex = (holder.getPage() * SLOTS_PER_PAGE) + rawSlot;

            if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                List<Pwarp> allWarps = this.pwarpService.getPublicWarps(holder.getSorting(), holder.getCategoryFilter());
                if (rawSlot < allWarps.size()) {
                    Pwarp warp = allWarps.get(rawSlot);
                    if (event.isRightClick()) {
                        openRateWarpGui(player, warp);
                    } else {
                        player.getScheduler().run(this.plugin, task -> player.closeInventory(), null);
                        this.pwarpService.executeTeleport(player, warp.name());
                    }
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

        switch (rawSlot) {
            case 45 -> { // Previous Page
                if (holder.getPage() > 0) {
                    if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                        openWarpsGui(player, holder.getPage() - 1, holder.getSorting(), holder.getCategoryFilter());
                    } else {
                        openMyWarpsGui(player, holder.getPage() - 1);
                    }
                }
            }
            case 46 -> { // Category Selector
                if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                    openCategoryGui(player);
                }
            }
            case 47 -> { // Sorting Selector
                if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                    PwarpSorting nextSorting = holder.getSorting().next();
                    openWarpsGui(player, 0, nextSorting, holder.getCategoryFilter());
                }
            }
            case 48 -> { // Toggle View
                if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                    openMyWarpsGui(player, 0);
                } else {
                    openWarpsGui(player, 0, PwarpSorting.MOST_VISITED, "all");
                }
            }
            case 50 -> { // Next Page
                if (holder.getPage() < holder.getTotalPages() - 1) {
                    if (holder.getViewType() == PwarpInventoryHolder.ViewType.ALL_WARPS) {
                        openWarpsGui(player, holder.getPage() + 1, holder.getSorting(), holder.getCategoryFilter());
                    } else {
                        openMyWarpsGui(player, holder.getPage() + 1);
                    }
                }
            }
            case 52 -> player.getScheduler().run(this.plugin, task -> player.closeInventory(), null);
        }
    }

    private void handleRateWarpClick(Player player, Pwarp warp, int slot) {
        if (slot == 22) { // Cancel
            openWarpsGui(player, 0);
            return;
        }

        int stars = switch (slot) {
            case 11 -> 1;
            case 12 -> 2;
            case 13 -> 3;
            case 14 -> 4;
            case 15 -> 5;
            default -> -1;
        };

        if (stars != -1 && warp != null) {
            player.getScheduler().run(this.plugin, task -> player.closeInventory(), null);
            this.pwarpService.rateWarp(player, warp.name(), stars);
        }
    }

    private void handleCategoryClick(Player player, int slot) {
        if (slot == 4 || slot == 49) {
            openWarpsGui(player, 0, PwarpSorting.MOST_VISITED, "all");
            return;
        }

        for (PwarpCategory cat : this.configSupplier.get().categories()) {
            if (cat.slot() == slot) {
                openWarpsGui(player, 0, PwarpSorting.MOST_VISITED, cat.key());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PwarpInventoryHolder) {
            event.setCancelled(true);
        }
    }
}
