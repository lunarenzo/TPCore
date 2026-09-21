package com.lunatech.tpcore.module.tpa;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.command.TpaCommandRegistry;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.gui.impl.DynamicConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.listener.TpaDialogListener;
import com.lunatech.tpcore.module.tpa.listener.TpaEventListener;
import com.lunatech.tpcore.module.tpa.listener.TpaGuiListener;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.module.tpa.repository.impl.ConcurrentTpaRepository;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import com.lunatech.tpcore.module.tpa.service.impl.DefaultTpaService;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class TpaModule implements ReloadableModule {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private volatile TpaConfig config;

    private TpaRepository repository;
    private TpaService service;
    private TpaConfirmationMenuService confirmationMenuService;
    private TpaEventListener listener;
    private TpaGuiListener guiListener;
    private TpaDialogListener dialogListener;
    private TpaCommandRegistry commandRegistry;

    public TpaModule(JavaPlugin plugin, ModularConfigManager configManager, TpaConfig config) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.config = config;
    }

    @Override
    public String getModuleName() {
        return "tpa";
    }

    @Override
    public boolean reloadConfig() {
        try {
            TpaConfig newConfig = this.configManager.tryLoadModuleConfig("tpa", TpaConfig.class, TpaConfig.createDefault());
            if (newConfig != null) {
                this.config = newConfig;
                if (this.service != null) {
                    this.service.updateConfig(newConfig);
                }
                return true;
            }
        } catch (Exception e) {
            this.plugin.getSLF4JLogger().error("Failed to reload TPA module configuration.", e);
        }
        return false;
    }

    public void enable() {
        if (!this.config.enabled()) {
            this.plugin.getSLF4JLogger().info("TPA Module is disabled in configuration. Skipping registration.");
            return;
        }

        this.repository = new ConcurrentTpaRepository();
        this.service = new DefaultTpaService(this.plugin, this.repository, this.config);
        this.confirmationMenuService = new DynamicConfirmationMenuService(() -> this.config, () -> this.service, this.plugin.getSLF4JLogger());
        this.listener = new TpaEventListener(this.service);
        this.guiListener = new TpaGuiListener(this.service, () -> this.config);
        this.dialogListener = new TpaDialogListener(this.plugin, () -> this.service);
        this.commandRegistry = new TpaCommandRegistry(this.plugin, this.service, () -> this.config, this.confirmationMenuService);

        this.plugin.getServer().getPluginManager().registerEvents(this.listener, this.plugin);
        this.plugin.getServer().getPluginManager().registerEvents(this.guiListener, this.plugin);
        this.dialogListener.registerIfSupported();
        this.commandRegistry.registerAll();
        this.configManager.registerModule(this);

        this.plugin.getSLF4JLogger().info("TPA Module successfully enabled.");
    }

    public void disable() {
        this.configManager.unregisterModule("tpa");
        if (this.listener != null) {
            HandlerList.unregisterAll(this.listener);
        }
        if (this.guiListener != null) {
            HandlerList.unregisterAll(this.guiListener);
        }
        if (this.dialogListener != null) {
            HandlerList.unregisterAll(this.dialogListener);
        }
        if (this.service != null) {
            this.service.shutdown();
        }
        if (this.repository != null) {
            this.repository.clear();
        }
        this.plugin.getSLF4JLogger().info("TPA Module disabled.");
    }

    public TpaService getService() {
        return this.service;
    }
}
