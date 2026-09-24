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

import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

public final class TpaGuiListener implements Listener {

    private final JavaPlugin plugin;
    private final TpaService tpaService;
    private final Supplier<TpaConfig> configSupplier;

    public TpaGuiListener(JavaPlugin plugin, TpaService tpaService, Supplier<TpaConfig> configSupplier) {
        this.plugin = plugin;
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
                if (slot == acceptSlot) {
                    closeInventoryDeferred(player);
                    if (holder.getTargetPlayerId() != null) {
                        this.tpaService.sendRequest(player, holder.getTargetPlayerId(), holder.getTpaType());
                    }
                } else if (slot == denySlot) {
                    closeInventoryDeferred(player);
                }
            } else if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.ACCEPT_REQUEST) {
                TpaRequest request = holder.getRequest();
                if (request == null || request.isExpired(cfg.requestTimeoutSeconds())) {
                    closeInventoryDeferred(player);
                    return;
                }

                if (slot == acceptSlot) {
                    closeInventoryDeferred(player);
                    String senderIdStr = request.senderId().toString();
                    TpaRequest pending = this.tpaService.findPendingRequest(player, senderIdStr);
                    if (pending != null && !pending.isExpired(cfg.requestTimeoutSeconds())) {
                        this.tpaService.acceptRequest(player, senderIdStr);
                    }
                } else if (slot == denySlot) {
                    closeInventoryDeferred(player);
                    String senderIdStr = request.senderId().toString();
                    TpaRequest pending = this.tpaService.findPendingRequest(player, senderIdStr);
                    if (pending != null && !pending.isExpired(cfg.requestTimeoutSeconds())) {
                        this.tpaService.denyRequest(player, senderIdStr);
                    }
                }
            }
        }
    }

    private void closeInventoryDeferred(Player player) {
        if (player != null && player.isOnline()) {
            player.getScheduler().run(this.plugin, t -> player.closeInventory(), null);
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
        if (event.getView().getTopInventory().getHolder() instanceof TpaConfirmationHolder) {
            event.setCancelled(true);
        }
    }

    private static final java.lang.reflect.Method GET_OFFLINE_PLAYER_IF_CACHED_UUID;
    static {
        java.lang.reflect.Method mUuid = null;
        try {
            mUuid = Bukkit.class.getMethod("getOfflinePlayerIfCached", java.util.UUID.class);
            mUuid.setAccessible(true);
        } catch (Throwable ignored) {
        }
        GET_OFFLINE_PLAYER_IF_CACHED_UUID = mUuid;
    }

    private static org.bukkit.OfflinePlayer resolveOfflinePlayerIfCached(java.util.UUID uuid) {
        if (GET_OFFLINE_PLAYER_IF_CACHED_UUID != null && uuid != null) {
            try {
                return (org.bukkit.OfflinePlayer) GET_OFFLINE_PLAYER_IF_CACHED_UUID.invoke(null, uuid);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
