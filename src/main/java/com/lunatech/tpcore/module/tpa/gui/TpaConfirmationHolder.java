package com.lunatech.tpcore.module.tpa.gui;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class TpaConfirmationHolder implements InventoryHolder {

    public enum ConfirmationType {
        SEND_REQUEST,
        ACCEPT_REQUEST
    }

    private final ConfirmationType confirmationType;
    private final TpaRequest request;
    private final UUID targetPlayerId;
    private final TpaType tpaType;

    public TpaConfirmationHolder(TpaRequest request) {
        this.confirmationType = ConfirmationType.ACCEPT_REQUEST;
        this.request = request;
        this.targetPlayerId = null;
        this.tpaType = (request != null) ? request.type() : TpaType.TPA_TO;
    }

    public TpaConfirmationHolder(UUID targetPlayerId, TpaType tpaType) {
        this.confirmationType = ConfirmationType.SEND_REQUEST;
        this.request = null;
        this.targetPlayerId = targetPlayerId;
        this.tpaType = tpaType;
    }

    public ConfirmationType getConfirmationType() {
        return this.confirmationType;
    }

    public TpaRequest getRequest() {
        return this.request;
    }

    public UUID getTargetPlayerId() {
        return this.targetPlayerId;
    }

    public TpaType getTpaType() {
        return this.tpaType;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
