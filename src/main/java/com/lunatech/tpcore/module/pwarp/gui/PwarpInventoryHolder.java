package com.lunatech.tpcore.module.pwarp.gui;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.model.PwarpSorting;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Custom zero-GC InventoryHolder marker for PlayerWarps chest GUI menus.
 */
public final class PwarpInventoryHolder implements InventoryHolder {

    public enum ViewType {
        ALL_WARPS,
        MY_WARPS,
        CATEGORY_SELECT,
        RATE_WARP
    }

    private final ViewType viewType;
    private final int page;
    private final int totalPages;
    private final PwarpSorting sorting;
    private final String categoryFilter;
    private final Pwarp targetWarp;
    private Inventory inventory;

    public PwarpInventoryHolder(ViewType viewType, int page, int totalPages) {
        this(viewType, page, totalPages, PwarpSorting.MOST_VISITED, "all", null);
    }

    public PwarpInventoryHolder(ViewType viewType, int page, int totalPages, PwarpSorting sorting, String categoryFilter) {
        this(viewType, page, totalPages, sorting, categoryFilter, null);
    }

    public PwarpInventoryHolder(ViewType viewType, Pwarp targetWarp) {
        this(viewType, 0, 1, PwarpSorting.MOST_VISITED, "all", targetWarp);
    }

    public PwarpInventoryHolder(ViewType viewType, int page, int totalPages, PwarpSorting sorting, String categoryFilter, Pwarp targetWarp) {
        this.viewType = viewType;
        this.page = page;
        this.totalPages = totalPages;
        this.sorting = sorting != null ? sorting : PwarpSorting.MOST_VISITED;
        this.categoryFilter = (categoryFilter != null && !categoryFilter.isBlank()) ? categoryFilter : "all";
        this.targetWarp = targetWarp;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    public ViewType getViewType() {
        return this.viewType;
    }

    public int getPage() {
        return this.page;
    }

    public int getTotalPages() {
        return this.totalPages;
    }

    public PwarpSorting getSorting() {
        return this.sorting;
    }

    public String getCategoryFilter() {
        return this.categoryFilter;
    }

    public Pwarp getTargetWarp() {
        return this.targetWarp;
    }
}
