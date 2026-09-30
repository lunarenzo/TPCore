package com.lunatech.tpcore.module.tpa.command;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Main command registry coordinator for the TPA module.
 * Safely hooks into Paper's LifecycleEvents.COMMANDS pipeline with idempotency guarantees.
 */
public final class TpaCommandRegistry {

    private final JavaPlugin plugin;
    private final TpaTeleportCommands teleportCommands;
    private final TpaManagementCommands managementCommands;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public TpaCommandRegistry(
        JavaPlugin plugin,
        TpaService tpaService,
        Supplier<TpaConfig> configSupplier,
        TpaConfirmationMenuService confirmationMenuService
    ) {
        this.plugin = plugin;
        this.teleportCommands = new TpaTeleportCommands(tpaService, configSupplier, confirmationMenuService);
        this.managementCommands = new TpaManagementCommands(tpaService, configSupplier, confirmationMenuService);
    }

    public void registerAll() {
        if (!this.registered.compareAndSet(false, true)) {
            return;
        }
        try {
            this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                final Commands commands = event.registrar();
                this.teleportCommands.register(commands);
                this.managementCommands.register(commands);
            });
        } catch (IllegalStateException e) {
            this.plugin.getSLF4JLogger().warn("Could not register TPA lifecycle commands (lifecycle phase already ended): {}", e.getMessage());
        }
    }
}
