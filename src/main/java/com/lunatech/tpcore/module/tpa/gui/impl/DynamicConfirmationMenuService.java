package com.lunatech.tpcore.module.tpa.gui.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import org.bukkit.plugin.java.JavaPlugin;

import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.economy.impl.NoOpTpaEconomyService;

import java.util.Locale;
import java.util.function.Supplier;

public final class DynamicConfirmationMenuService implements TpaConfirmationMenuService {

    private final Supplier<TpaConfig> configSupplier;
    private final ChestGuiConfirmationService chestGuiService;
    private final PaperDialogConfirmationService paperDialogService;

    public DynamicConfirmationMenuService(
        JavaPlugin plugin,
        Supplier<TpaConfig> configSupplier,
        Supplier<TpaService> serviceSupplier,
        Logger logger
    ) {
        this(plugin, configSupplier, serviceSupplier, () -> new NoOpTpaEconomyService(), logger);
    }

    public DynamicConfirmationMenuService(
        JavaPlugin plugin,
        Supplier<TpaConfig> configSupplier,
        Supplier<TpaService> serviceSupplier,
        Supplier<TpaEconomyService> economyServiceSupplier,
        Logger logger
    ) {
        this.configSupplier = configSupplier;
        this.chestGuiService = new ChestGuiConfirmationService(plugin, configSupplier, economyServiceSupplier);
        this.paperDialogService = new PaperDialogConfirmationService(plugin, configSupplier, serviceSupplier, economyServiceSupplier, logger);
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
        } else {
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
        } else {
            this.chestGuiService.openSendConfirmation(sender, target, type);
        }
    }

    private String getNormalizedMode(TpaConfig config) {
        if (config == null || config.confirmationMode() == null) {
            return "GUI";
        }
        return config.confirmationMode().trim().toUpperCase(Locale.ROOT);
    }
}
