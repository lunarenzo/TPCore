package com.lunatech.tpcore.module.tpa.gui;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class TpaConfirmationHolder implements InventoryHolder {

    private final TpaRequest request;

    public TpaConfirmationHolder(TpaRequest request) {
        this.request = request;
    }

    public TpaRequest getRequest() {
        return this.request;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
