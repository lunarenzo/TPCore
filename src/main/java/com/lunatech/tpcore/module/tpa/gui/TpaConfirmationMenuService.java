package com.lunatech.tpcore.module.tpa.gui;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import org.bukkit.entity.Player;

public interface TpaConfirmationMenuService {
    void openConfirmation(Player target, TpaRequest request);
}
