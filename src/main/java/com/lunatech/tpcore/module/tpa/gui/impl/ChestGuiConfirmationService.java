package com.lunatech.tpcore.module.tpa.gui.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationHolder;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.function.Supplier;

public final class ChestGuiConfirmationService implements TpaConfirmationMenuService {

    private final Supplier<TpaConfig> configSupplier;
    private final MiniMessage miniMessage;

    public ChestGuiConfirmationService(Supplier<TpaConfig> configSupplier) {
        this.configSupplier = configSupplier;
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public void openConfirmation(Player target, TpaRequest request) {
        if (target == null || !target.isOnline() || request == null) {
            return;
        }

        TpaConfig cfg = this.configSupplier.get();
        Component title = formatComponent(cfg.guiTitle());

        Inventory inventory = Bukkit.createInventory(new TpaConfirmationHolder(request), 27, title);

        if (cfg.guiFillEmptySlots()) {
            Material fillMat = parseMaterial(cfg.guiFillItem(), Material.GRAY_STAINED_GLASS_PANE);
            ItemStack fillItem = createItem(fillMat, "<gray> </gray>");
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, fillItem.clone());
            }
        }

        // Slot 13: Player Head
        Player senderPlayer = Bukkit.getPlayer(request.senderId());
        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
        if (skullMeta != null) {
            if (senderPlayer != null && senderPlayer.isOnline()) {
                skullMeta.setPlayerProfile(senderPlayer.getPlayerProfile());
            } else {
                OfflinePlayer offlineSender = Bukkit.getOfflinePlayer(request.senderId());
                skullMeta.setOwningPlayer(offlineSender);
            }

            String senderName = (senderPlayer != null) ? senderPlayer.getName() : "Player";
            skullMeta.displayName(formatComponent("<yellow><bold>" + senderName + "</bold></yellow>"));

            String reqTypeText = (request.type() == TpaType.TPA_HERE)
                ? "<gray>Request Type: <gold>TPA Here (Teleport to them)</gold></gray>"
                : "<gray>Request Type: <gold>TPA (Teleport to you)</gold></gray>";

            skullMeta.lore(List.of(
                formatComponent(reqTypeText),
                formatComponent("<gray>Expires in: <gold>" + cfg.requestTimeoutSeconds() + "s</gold></gray>")
            ));

            headItem.setItemMeta(skullMeta);
        }
        int headSlot = clampSlot(cfg.guiHeadSlot(), 13);
        inventory.setItem(headSlot, headItem);

        // Slot 15: Accept Button
        Material acceptMat = parseMaterial(cfg.guiAcceptItem(), Material.LIME_STAINED_GLASS_PANE);
        String acceptName = (cfg.guiAcceptName() != null && !cfg.guiAcceptName().isBlank())
            ? cfg.guiAcceptName()
            : "<green><bold>ACCEPT REQUEST</bold></green>";
        ItemStack acceptItem = createItem(acceptMat, acceptName);
        int acceptSlot = clampSlot(cfg.guiAcceptSlot(), 15);
        inventory.setItem(acceptSlot, acceptItem);

        // Slot 11: Deny Button
        Material denyMat = parseMaterial(cfg.guiDenyItem(), Material.RED_STAINED_GLASS_PANE);
        String denyName = (cfg.guiDenyName() != null && !cfg.guiDenyName().isBlank())
            ? cfg.guiDenyName()
            : "<red><bold>DENY REQUEST</bold></red>";
        ItemStack denyItem = createItem(denyMat, denyName);
        int denySlot = clampSlot(cfg.guiDenySlot(), 11);
        inventory.setItem(denySlot, denyItem);

        target.openInventory(inventory);
    }

    private ItemStack createItem(Material material, String nameMiniMessage) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(formatComponent(nameMiniMessage));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Component formatComponent(String miniMessageText) {
        if (miniMessageText == null || miniMessageText.isBlank()) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        return this.miniMessage.deserialize(miniMessageText).decoration(TextDecoration.ITALIC, false);
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            Material mat = Material.valueOf(name.toUpperCase());
            return mat;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private int clampSlot(int slot, int defaultSlot) {
        if (slot < 0 || slot >= 27) {
            return defaultSlot;
        }
        return slot;
    }
}
