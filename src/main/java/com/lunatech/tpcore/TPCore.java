package com.lunatech.tpcore;

import com.lunatech.tpcore.command.TPCoreAdminCommandRegistry;
import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.back.BackModule;
import com.lunatech.tpcore.module.home.HomeModule;
import com.lunatech.tpcore.module.rtp.RtpModule;
import com.lunatech.tpcore.module.spawn.SpawnModule;
import com.lunatech.tpcore.module.tpa.TpaModule;
import com.lunatech.tpcore.module.warp.WarpModule;
import com.lunatech.tpcore.platform.ServerVersion;
import org.bukkit.plugin.java.JavaPlugin;

public final class TPCore extends JavaPlugin {

    private ModularConfigManager configManager;
    private TpaModule tpaModule;
    private SpawnModule spawnModule;
    private HomeModule homeModule;
    private WarpModule warpModule;
    private BackModule backModule;
    private RtpModule rtpModule;
    private TPCoreAdminCommandRegistry adminCommandRegistry;

    @Override
    public void onEnable() {
        this.getSLF4JLogger().info("Initializing TPCore on Minecraft {}.{}.{}...", ServerVersion.MAJOR, ServerVersion.MINOR, ServerVersion.PATCH);

        this.configManager = new ModularConfigManager(
            this.getDataFolder().toPath(),
            this.getSLF4JLogger(),
            this.getClassLoader()
        );
        this.configManager.initializeDirectories();
        this.configManager.loadCoreConfig();

        TpaConfig tpaConfig = this.configManager.loadModuleConfig("tpa", TpaConfig.class, TpaConfig.createDefault());
        this.tpaModule = new TpaModule(this, this.configManager, tpaConfig);
        this.tpaModule.enable();

        SpawnConfig spawnConfig = this.configManager.loadModuleConfig("spawn", SpawnConfig.class, SpawnConfig.createDefault());
        this.spawnModule = new SpawnModule(this, this.configManager, spawnConfig);
        this.spawnModule.enable();

        HomeConfig homeConfig = this.configManager.loadModuleConfig("home", HomeConfig.class, HomeConfig.createDefault());
        this.homeModule = new HomeModule(this, this.configManager, homeConfig);
        this.homeModule.enable();

        WarpConfig warpConfig = this.configManager.loadModuleConfig("warp", WarpConfig.class, WarpConfig.createDefault());
        this.warpModule = new WarpModule(this, this.configManager, warpConfig);
        this.warpModule.enable();

        BackConfig backConfig = this.configManager.loadModuleConfig("back", BackConfig.class, BackConfig.createDefault());
        this.backModule = new BackModule(this, this.configManager, backConfig);
        this.backModule.enable();

        this.rtpModule = new RtpModule(this, this.configManager);
        this.rtpModule.enable();

        this.adminCommandRegistry = new TPCoreAdminCommandRegistry(this, this.configManager, this.configManager::getCoreConfig);
        this.adminCommandRegistry.registerAll();

        this.getSLF4JLogger().info("TPCore successfully loaded and enabled!");
    }

    @Override
    public void onDisable() {
        if (this.rtpModule != null) {
            this.rtpModule.disable();
        }
        if (this.backModule != null) {
            this.backModule.disable();
        }
        if (this.warpModule != null) {
            this.warpModule.disable();
        }
        if (this.homeModule != null) {
            this.homeModule.disable();
        }
        if (this.spawnModule != null) {
            this.spawnModule.disable();
        }
        if (this.tpaModule != null) {
            this.tpaModule.disable();
        }
        this.getSLF4JLogger().info("TPCore successfully disabled.");
    }
}
