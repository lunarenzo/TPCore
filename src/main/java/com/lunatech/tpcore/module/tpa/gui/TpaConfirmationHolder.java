package com.lunatech.tpcore.module.tpa.gui;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class TpaConfirmationHolder implements InventoryHolder {

    public enum ConfirmationType {
        SEND_REQUEST,
        ACCEPT_REQUEST
    }

    private final ConfirmationType confirmationType;
    private final TpaRequest request;
    private final Player targetPlayer;
    private final TpaType tpaType;

    public TpaConfirmationHolder(TpaRequest request) {
        this.confirmationType = ConfirmationType.ACCEPT_REQUEST;
        this.request = request;
        this.targetPlayer = null;
        this.tpaType = (request != null) ? request.type() : TpaType.TPA_TO;
    }

    public TpaConfirmationHolder(Player targetPlayer, TpaType tpaType) {
        this.confirmationType = ConfirmationType.SEND_REQUEST;
        this.request = null;
        this.targetPlayer = targetPlayer;
        this.tpaType = tpaType;
    }

    public ConfirmationType getConfirmationType() {
        return this.confirmationType;
    }

    public TpaRequest getRequest() {
        return this.request;
    }

    public Player getTargetPlayer() {
        return this.targetPlayer;
    }

    public TpaType getTpaType() {
        return this.tpaType;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
