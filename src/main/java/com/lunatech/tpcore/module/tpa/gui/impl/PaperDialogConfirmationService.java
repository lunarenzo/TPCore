package com.lunatech.tpcore.module.tpa.gui.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.platform.ServerVersion;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class PaperDialogConfirmationService implements TpaConfirmationMenuService {

    private final Supplier<TpaConfig> configSupplier;
    private final ChestGuiConfirmationService fallbackChestGui;
    private final Logger logger;

    public PaperDialogConfirmationService(Supplier<TpaConfig> configSupplier, Logger logger) {
        this.configSupplier = configSupplier;
        this.fallbackChestGui = new ChestGuiConfirmationService(configSupplier);
        this.logger = logger;
    }

    @Override
    public void openConfirmation(Player target, TpaRequest request) {
        if (target == null || !target.isOnline() || request == null) {
            return;
        }

        if (!ServerVersion.IS_DIALOG_SUPPORTED) {
            this.fallbackChestGui.openConfirmation(target, request);
            return;
        }

        // On Paper 1.21.6+, if Paper Dialog API is present at runtime, invoke it safely.
        // Otherwise, delegate to fallback chest GUI.
        try {
            Class<?> dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");
            if (dialogClass != null) {
                // Future expansion for native Paper 1.21.6+ Dialog builder
                this.fallbackChestGui.openConfirmation(target, request);
                return;
            }
        } catch (ClassNotFoundException e) {
            // Log fallback once or delegate silently
        }

        this.fallbackChestGui.openConfirmation(target, request);
    }
}
