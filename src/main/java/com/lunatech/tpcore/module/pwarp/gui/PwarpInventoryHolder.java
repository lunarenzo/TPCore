package com.lunatech.tpcore.module.pwarp.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Custom zero-GC InventoryHolder marker for PlayerWarps chest GUI menus.
 */
public final class PwarpInventoryHolder implements InventoryHolder {

    public enum ViewType {
        ALL_WARPS,
        MY_WARPS
    }

    private final ViewType viewType;
    private final int page;
    private final int totalPages;
    private Inventory inventory;

    public PwarpInventoryHolder(ViewType viewType, int page, int totalPages) {
        this.viewType = viewType;
        this.page = page;
        this.totalPages = totalPages;
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
}
