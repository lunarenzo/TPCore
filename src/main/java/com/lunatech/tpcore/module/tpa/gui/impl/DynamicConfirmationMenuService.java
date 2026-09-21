package com.lunatech.tpcore.module.tpa.gui.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class DynamicConfirmationMenuService implements TpaConfirmationMenuService {

    private final Supplier<TpaConfig> configSupplier;
    private final ChestGuiConfirmationService chestGuiService;
    private final PaperDialogConfirmationService paperDialogService;

    public DynamicConfirmationMenuService(
        Supplier<TpaConfig> configSupplier,
        Supplier<TpaService> serviceSupplier,
        Logger logger
    ) {
        this.configSupplier = configSupplier;
        this.chestGuiService = new ChestGuiConfirmationService(configSupplier);
        this.paperDialogService = new PaperDialogConfirmationService(configSupplier, serviceSupplier, logger);
    }

    @Override
    public void openAcceptConfirmation(Player target, TpaRequest request) {
        if (target == null || !target.isOnline() || request == null) {
            return;
        }

        TpaConfig config = this.configSupplier.get();
        String mode = getNormalizedMode(config);

        if ("DIALOG".equals(mode)) {
            this.paperDialogService.openAcceptConfirmation(target, request);
        } else if ("GUI".equals(mode)) {
            this.chestGuiService.openAcceptConfirmation(target, request);
        }
    }

    @Override
    public void openSendConfirmation(Player sender, Player target, TpaType type) {
        if (sender == null || !sender.isOnline() || target == null) {
            return;
        }

        TpaConfig config = this.configSupplier.get();
        String mode = getNormalizedMode(config);

        if ("DIALOG".equals(mode)) {
            this.paperDialogService.openSendConfirmation(sender, target, type);
        } else if ("GUI".equals(mode)) {
            this.chestGuiService.openSendConfirmation(sender, target, type);
        }
    }

    private String getNormalizedMode(TpaConfig config) {
        if (config == null || config.confirmationMode() == null) {
            return "GUI";
        }
        return config.confirmationMode().trim().toUpperCase();
    }
}
