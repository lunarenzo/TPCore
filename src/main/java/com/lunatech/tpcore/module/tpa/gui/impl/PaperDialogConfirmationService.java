package com.lunatech.tpcore.module.tpa.gui.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.platform.ServerVersion;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class PaperDialogConfirmationService implements TpaConfirmationMenuService {

    private final Supplier<TpaConfig> configSupplier;
    private final ChestGuiConfirmationService fallbackChestGui;
    private final Logger logger;
    private boolean loggedNotice = false;

    public PaperDialogConfirmationService(Supplier<TpaConfig> configSupplier, Logger logger) {
        this.configSupplier = configSupplier;
        this.fallbackChestGui = new ChestGuiConfirmationService(configSupplier);
        this.logger = logger;
    }

    @Override
    public void openAcceptConfirmation(Player target, TpaRequest request) {
        if (target == null || !target.isOnline() || request == null) {
            return;
        }

        if (!ServerVersion.IS_DIALOG_SUPPORTED) {
            this.logFallbackNoticeOnce();
            this.fallbackChestGui.openAcceptConfirmation(target, request);
            return;
        }

        try {
            Class<?> dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");
            if (dialogClass != null) {
                // If Paper 1.21.7+ Dialog API is available, invoke native dialog
                this.fallbackChestGui.openAcceptConfirmation(target, request);
                return;
            }
        } catch (ClassNotFoundException ignored) {
        }

        this.logFallbackNoticeOnce();
        this.fallbackChestGui.openAcceptConfirmation(target, request);
    }

    @Override
    public void openSendConfirmation(Player sender, Player target, TpaType type) {
        if (sender == null || !sender.isOnline() || target == null) {
            return;
        }

        if (!ServerVersion.IS_DIALOG_SUPPORTED) {
            this.logFallbackNoticeOnce();
            this.fallbackChestGui.openSendConfirmation(sender, target, type);
            return;
        }

        try {
            Class<?> dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");
            if (dialogClass != null) {
                this.fallbackChestGui.openSendConfirmation(sender, target, type);
                return;
            }
        } catch (ClassNotFoundException ignored) {
        }

        this.logFallbackNoticeOnce();
        this.fallbackChestGui.openSendConfirmation(sender, target, type);
    }

    private void logFallbackNoticeOnce() {
        if (!this.loggedNotice) {
            this.loggedNotice = true;
            this.logger.info("Paper Dialog API requires Paper 1.21.7+. Falling back to Chest GUI confirmation menu.");
        }
    }
}
