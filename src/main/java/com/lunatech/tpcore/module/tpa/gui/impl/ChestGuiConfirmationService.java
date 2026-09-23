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
    public void openAcceptConfirmation(Player target, TpaRequest request) {
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

        // Slot 13: Sender's Player Head
        Player senderPlayer = Bukkit.getPlayer(request.senderId());
        String senderName = (senderPlayer != null) ? senderPlayer.getName() : "Player";
        if (senderPlayer == null) {
            OfflinePlayer offlineSender = Bukkit.getOfflinePlayer(request.senderId());
            if (offlineSender.hasPlayedBefore() || offlineSender.isOnline()) {
                String cachedName = offlineSender.getName();
                if (cachedName != null) {
                    senderName = cachedName;
                }
            }
        }

        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
        if (skullMeta != null) {
            if (senderPlayer != null && senderPlayer.isOnline()) {
                skullMeta.setPlayerProfile(senderPlayer.getPlayerProfile());
            } else {
                skullMeta.setPlayerProfile(Bukkit.createProfile(request.senderId(), senderName));
            }

            skullMeta.displayName(formatComponent("<yellow><bold>" + senderName + "</bold></yellow>"));

            String reqTypeConfig = (request.type() == TpaType.TPA_HERE) ? cfg.dialogAcceptTpahereBodyText() : cfg.dialogAcceptTpaBodyText();
            String reqTypeText = (reqTypeConfig != null && !reqTypeConfig.isBlank())
                ? reqTypeConfig
                : ((request.type() == TpaType.TPA_HERE)
                    ? "<gray>Request Type: <gold>TPA Here (Teleport to them)</gold></gray>"
                    : "<gray>Request Type: <gold>TPA (Teleport to you)</gold></gray>");

            skullMeta.lore(List.of(
                formatComponent(reqTypeText),
                formatComponent("<gray>Expires in: <gold>" + cfg.requestTimeoutSeconds() + "s</gold></gray>")
            ));

            headItem.setItemMeta(skullMeta);
        }
        int headSlot = clampSlot(cfg.guiHeadSlot(), 13);
        int acceptSlot = clampSlot(cfg.guiAcceptSlot(), 15);
        int denySlot = clampSlot(cfg.guiDenySlot(), 11);

        if (headSlot == acceptSlot || headSlot == denySlot || acceptSlot == denySlot) {
            headSlot = 13;
            acceptSlot = 15;
            denySlot = 11;
        }

        inventory.setItem(headSlot, headItem);

        // Accept Button
        Material acceptMat = parseMaterial(cfg.guiAcceptItem(), Material.LIME_STAINED_GLASS_PANE);
        String acceptName = (cfg.guiAcceptName() != null && !cfg.guiAcceptName().isBlank())
            ? cfg.guiAcceptName()
            : "<green><bold>ACCEPT REQUEST</bold></green>";
        ItemStack acceptItem = createItem(acceptMat, acceptName);
        inventory.setItem(acceptSlot, acceptItem);

        // Deny Button
        Material denyMat = parseMaterial(cfg.guiDenyItem(), Material.RED_STAINED_GLASS_PANE);
        String denyName = (cfg.guiDenyName() != null && !cfg.guiDenyName().isBlank())
            ? cfg.guiDenyName()
            : "<red><bold>DENY REQUEST</bold></red>";
        ItemStack denyItem = createItem(denyMat, denyName);
        inventory.setItem(denySlot, denyItem);

        target.openInventory(inventory);
    }

    @Override
    public void openSendConfirmation(Player sender, Player target, TpaType type) {
        if (sender == null || !sender.isOnline() || target == null) {
            return;
        }

        TpaConfig cfg = this.configSupplier.get();
        String titleStr = (cfg.dialogTitle() != null && !cfg.dialogTitle().isBlank())
            ? cfg.dialogTitle()
            : "<gradient:#00D2FF:#3A7BD5><bold>Send Teleport Request</bold></gradient>";
        Component title = formatComponent(titleStr);

        Inventory inventory = Bukkit.createInventory(new TpaConfirmationHolder(target, type), 27, title);

        if (cfg.guiFillEmptySlots()) {
            Material fillMat = parseMaterial(cfg.guiFillItem(), Material.GRAY_STAINED_GLASS_PANE);
            ItemStack fillItem = createItem(fillMat, "<gray> </gray>");
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, fillItem.clone());
            }
        }

        int headSlot = clampSlot(cfg.guiHeadSlot(), 13);
        int acceptSlot = clampSlot(cfg.guiAcceptSlot(), 15);
        int denySlot = clampSlot(cfg.guiDenySlot(), 11);

        if (headSlot == acceptSlot || headSlot == denySlot || acceptSlot == denySlot) {
            headSlot = 13;
            acceptSlot = 15;
            denySlot = 11;
        }

        // Target's Player Head
        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
        if (skullMeta != null) {
            if (target.isOnline()) {
                skullMeta.setPlayerProfile(target.getPlayerProfile());
            } else {
                skullMeta.setPlayerProfile(Bukkit.createProfile(target.getUniqueId(), target.getName()));
            }

            skullMeta.displayName(formatComponent("<yellow><bold>" + target.getName() + "</bold></yellow>"));

            String reqTypeConfig = (type == TpaType.TPA_HERE) ? cfg.dialogSendTpahereBodyText() : cfg.dialogSendTpaBodyText();
            String reqTypeText = (reqTypeConfig != null && !reqTypeConfig.isBlank())
                ? reqTypeConfig
                : ((type == TpaType.TPA_HERE)
                    ? "<gray>Request Type: <gold>TPA Here (Ask them to teleport to you)</gold></gray>"
                    : "<gray>Request Type: <gold>TPA (Teleport to their location)</gold></gray>");

            skullMeta.lore(List.of(
                formatComponent(reqTypeText),
                formatComponent("<gray>Timeout: <gold>" + cfg.requestTimeoutSeconds() + "s</gold></gray>")
            ));

            headItem.setItemMeta(skullMeta);
        }
        inventory.setItem(headSlot, headItem);

        // Confirm Send Button
        Material acceptMat = parseMaterial(cfg.guiAcceptItem(), Material.LIME_STAINED_GLASS_PANE);
        String sendConfirmName = (cfg.dialogSendConfirmText() != null && !cfg.dialogSendConfirmText().isBlank())
            ? cfg.dialogSendConfirmText()
            : "<green><bold>CONFIRM & SEND</bold></green>";
        ItemStack acceptItem = createItem(acceptMat, sendConfirmName);
        inventory.setItem(acceptSlot, acceptItem);

        // Cancel Button
        Material denyMat = parseMaterial(cfg.guiDenyItem(), Material.RED_STAINED_GLASS_PANE);
        String sendCancelName = (cfg.dialogSendCancelText() != null && !cfg.dialogSendCancelText().isBlank())
            ? cfg.dialogSendCancelText()
            : "<red><bold>CANCEL</bold></red>";
        ItemStack denyItem = createItem(denyMat, sendCancelName);
        inventory.setItem(denySlot, denyItem);

        sender.openInventory(inventory);
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
            return Material.valueOf(name.toUpperCase());
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
