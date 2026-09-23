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

            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }

            TpaConfig cfg = this.configSupplier.get();
            int slot = event.getRawSlot();

            if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.SEND_REQUEST) {
                Player target = holder.getTargetPlayer();
                if (slot == cfg.guiAcceptSlot()) {
                    player.closeInventory();
                    if (target != null && target.isOnline()) {
                        this.tpaService.sendRequest(player, target, holder.getTpaType());
                    }
                } else if (slot == cfg.guiDenySlot()) {
                    player.closeInventory();
                }
            } else if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.ACCEPT_REQUEST) {
                TpaRequest request = holder.getRequest();
                if (request == null || request.isExpired(cfg.requestTimeoutSeconds())) {
                    player.closeInventory();
                    return;
                }

                if (slot == cfg.guiAcceptSlot()) {
                    player.closeInventory();
                    String senderIdStr = request.senderId().toString();
                    TpaRequest pending = this.tpaService.findPendingRequest(player, senderIdStr);
                    if (pending != null && !pending.isExpired(cfg.requestTimeoutSeconds())) {
                        this.tpaService.acceptRequest(player, senderIdStr);
                    }
                } else if (slot == cfg.guiDenySlot()) {
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TpaConfirmationHolder) {
            event.setCancelled(true);
        }
    }
}
