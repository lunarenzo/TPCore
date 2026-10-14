package com.lunatech.tpcore.module.warp.gui;

import com.lunatech.tpcore.module.warp.model.WarpBrowseQuery;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import net.kyori.adventure.text.Component;

/** Server-owned view only; managers retain UUIDs, never this holder or its inventory. */
public final class WarpBrowserHolder implements InventoryHolder {
    private final Inventory inventory;
    private final UUID owner;
    private final long token;
    private final WarpBrowseQuery query;
    private final String[] entries;

    public WarpBrowserHolder(UUID owner, long token, WarpBrowseQuery query, int size, Component title) {
        this.owner = owner; this.token = token; this.query = query;
        this.entries = new String[size - 9];
        this.inventory = Bukkit.createInventory(this, size, title);
    }
    @Override public Inventory getInventory() { return inventory; }
    public UUID owner() { return owner; }
    public long token() { return token; }
    public WarpBrowseQuery query() { return query; }
    public int contentSize() { return entries.length; }
    public void entry(int slot, String value) { entries[slot] = value; }
    public String entry(int slot) { return slot >= 0 && slot < entries.length ? entries[slot] : null; }
}
