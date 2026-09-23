package com.lunatech.tpcore.module.tpa.listener;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationHolder;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.function.Supplier;

public final class TpaGuiListener implements Listener {

    private final TpaService tpaService;
    private final Supplier<TpaConfig> configSupplier;

    public TpaGuiListener(TpaService tpaService, Supplier<TpaConfig> configSupplier) {
        this.tpaService = tpaService;
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof TpaConfirmationHolder holder) {
            event.setCancelled(true);

            if (event.getClickedInventory() != event.getInventory()) {
                return;
            }

            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }

            TpaConfig cfg = this.configSupplier.get();
            int slot = event.getRawSlot();
            int headSlot = clampSlot(cfg.guiHeadSlot(), 13);
            int acceptSlot = clampSlot(cfg.guiAcceptSlot(), 15);
            int denySlot = clampSlot(cfg.guiDenySlot(), 11);

            if (headSlot == acceptSlot || headSlot == denySlot || acceptSlot == denySlot) {
                headSlot = 13;
                acceptSlot = 15;
                denySlot = 11;
            }

            if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.SEND_REQUEST) {
                Player target = (holder.getTargetPlayerId() != null) ? Bukkit.getPlayer(holder.getTargetPlayerId()) : null;
                if (slot == acceptSlot) {
                    player.closeInventory();
                    if (target != null && target.isOnline()) {
                        this.tpaService.sendRequest(player, target, holder.getTpaType());
                    }
                } else if (slot == denySlot) {
                    player.closeInventory();
                }
            } else if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.ACCEPT_REQUEST) {
                TpaRequest request = holder.getRequest();
                if (request == null || request.isExpired(cfg.requestTimeoutSeconds())) {
                    player.closeInventory();
                    return;
                }

                if (slot == acceptSlot) {
                    player.closeInventory();
                    String senderIdStr = request.senderId().toString();
                    TpaRequest pending = this.tpaService.findPendingRequest(player, senderIdStr);
                    if (pending != null && !pending.isExpired(cfg.requestTimeoutSeconds())) {
                        this.tpaService.acceptRequest(player, senderIdStr);
                    }
                } else if (slot == denySlot) {
                    player.closeInventory();
                    String senderIdStr = request.senderId().toString();
                    TpaRequest pending = this.tpaService.findPendingRequest(player, senderIdStr);
                    if (pending != null && !pending.isExpired(cfg.requestTimeoutSeconds())) {
                        this.tpaService.denyRequest(player, senderIdStr);
                    }
                }
            }
        }
    }

    private static int clampSlot(int rawSlot, int defaultSlot) {
        if (rawSlot < 0 || rawSlot > 26) {
            return defaultSlot;
        }
        return rawSlot;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TpaConfirmationHolder) {
            event.setCancelled(true);
        }
    }
}
