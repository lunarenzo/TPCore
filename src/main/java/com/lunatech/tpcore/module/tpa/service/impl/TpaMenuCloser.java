package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationHolder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Handles graceful dismissal of active confirmation menus and Paper dialogs.
 */
final class TpaMenuCloser {

    private static final Method CLOSE_DIALOG;

    static {
        Method m = null;
        try {
            m = Player.class.getMethod("closeDialog");
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }
        CLOSE_DIALOG = m;
    }

    private TpaMenuCloser() {
    }

    static void closeConfirmationMenuIfOpen(Player player) {
        closeConfirmationMenuIfOpen(player, null);
    }

    static void closeConfirmationMenuIfOpen(Player player, UUID expectedOtherPlayerId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean matches = (expectedOtherPlayerId == null);
        try {
            InventoryView view = player.getOpenInventory();
            Inventory top = (view != null) ? view.getTopInventory() : null;
            if (top != null && top.getHolder() instanceof TpaConfirmationHolder holder) {
                if (expectedOtherPlayerId != null) {
                    if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.ACCEPT_REQUEST && holder.getRequest() != null) {
                        matches = expectedOtherPlayerId.equals(holder.getRequest().senderId());
                    } else if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.SEND_REQUEST && holder.getTargetPlayerId() != null) {
                        matches = expectedOtherPlayerId.equals(holder.getTargetPlayerId());
                    }
                }
                if (matches) {
                    player.closeInventory();
                }
            }
        } catch (Throwable ignored) {
        }
        if (CLOSE_DIALOG != null) {
            try {
                CLOSE_DIALOG.invoke(player);
            } catch (Throwable ignored) {
            }
        }
    }
}
