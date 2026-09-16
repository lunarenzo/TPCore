package com.lunatech.tpcore;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.TpaModule;
import com.lunatech.tpcore.platform.ServerVersion;
import org.bukkit.plugin.java.JavaPlugin;

public final class TPCore extends JavaPlugin {

    private ModularConfigManager configManager;
    private TpaModule tpaModule;

    @Override
    public void onEnable() {
        this.getSLF4JLogger().info("Initializing TPCore on Minecraft {}.{}.{}...", ServerVersion.MAJOR, ServerVersion.MINOR, ServerVersion.PATCH);

        this.configManager = new ModularConfigManager(
            this.getDataFolder().toPath(),
            this.getSLF4JLogger(),
            this.getClassLoader()
        );
        this.configManager.initializeDirectories();

        TpaConfig tpaConfig = this.configManager.loadModuleConfig("tpa", TpaConfig.class, TpaConfig.createDefault());

        this.tpaModule = new TpaModule(this, tpaConfig);
        this.tpaModule.enable();

        this.getSLF4JLogger().info("TPCore successfully loaded and enabled!");
    }

    @Override
    public void onDisable() {
        if (this.tpaModule != null) {
            this.tpaModule.disable();
        }
        this.getSLF4JLogger().info("TPCore successfully disabled.");
    }
}
