package com.lunatech.tpcore.module.tpa.gui;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.bukkit.entity.Player;

public interface TpaConfirmationMenuService {

    void openAcceptConfirmation(Player target, TpaRequest request);

    void openSendConfirmation(Player sender, Player target, TpaType type);

    default void openConfirmation(Player target, TpaRequest request) {
        openAcceptConfirmation(target, request);
    }
}
