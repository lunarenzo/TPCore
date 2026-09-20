package com.lunatech.tpcore.module.pwarp.config;

import com.lunatech.tpcore.config.ModularConfigManager;
import java.util.Objects;
import org.slf4j.Logger;

public final class PwarpConfigManager {

    private final ModularConfigManager configManager;
    private final Logger logger;
    private volatile PwarpConfig activeConfig;

    public PwarpConfigManager(ModularConfigManager configManager, Logger logger) {
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    public PwarpConfig load() {
        try {
            PwarpConfig loaded = this.configManager.tryLoadModuleConfig("pwarp", PwarpConfig.class, PwarpConfig.createDefault());
            if (loaded != null) {
                this.activeConfig = loaded;
                return loaded;
            }
        } catch (Exception e) {
            this.logger.error("Failed to load pwarp.yml configuration file. Falling back to default settings.", e);
        }

        if (this.activeConfig == null) {
            this.activeConfig = PwarpConfig.createDefault();
        }
        return this.activeConfig;
    }

    public PwarpConfig getActiveConfig() {
        if (this.activeConfig == null) {
            return load();
        }
        return this.activeConfig;
    }
}
