package com.lunatech.tpcore.module.home.gui;

import com.lunatech.tpcore.module.home.model.HomePage;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class HomeMenuHolder implements InventoryHolder {
    public enum Screen { MENU, HOMES, CONFIRM }

    private final UUID player;
    private final UUID token;
    private final Screen screen;
    private final long page;
    private final long pages;
    private final List<HomePage.Entry> entries;
    private final HomePage.Entry selected;
    private final Inventory inventory;
    private boolean closing;

    HomeMenuHolder(UUID player, UUID token, Screen screen, long page, long pages, List<HomePage.Entry> entries,
        HomePage.Entry selected, Component title) {
        this.player = player;
        this.token = token;
        this.screen = screen;
        this.page = page;
        this.pages = pages;
        this.entries = List.copyOf(entries);
        this.selected = selected;
        inventory = Bukkit.createInventory(this, screen == Screen.HOMES ? 54 : 27, title);
    }

    public UUID player() { return player; }
    public UUID token() { return token; }
    Screen screen() { return screen; }
    long page() { return page; }
    long pages() { return pages; }
    List<HomePage.Entry> entries() { return entries; }
    HomePage.Entry selected() { return selected; }
    boolean beginClose() {
        if (closing) return false;
        closing = true;
        return true;
    }
    @Override public Inventory getInventory() { return inventory; }
}
