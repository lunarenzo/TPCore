package com.lunatech.tpcore.module.rtp.config;

import com.lunatech.tpcore.config.ModularConfigManager;
import java.util.Objects;
import org.slf4j.Logger;

public final class RtpConfigManager {

    private final ModularConfigManager modularConfigManager;
    private final Logger logger;
    private volatile RtpConfig activeConfig;

    public RtpConfigManager(ModularConfigManager modularConfigManager, Logger logger) {
        this.modularConfigManager = Objects.requireNonNull(modularConfigManager, "modularConfigManager cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.activeConfig = RtpConfig.createDefault();
    }

    public RtpConfig getActiveConfig() {
        return this.activeConfig;
    }

    public RtpConfig load() {
        try {
            RtpConfig loaded = this.modularConfigManager.loadModuleConfig("rtp", RtpConfig.class, RtpConfig.createDefault());
            if (loaded != null) {
                this.activeConfig = loaded;
                return loaded;
            }
        } catch (Exception e) {
            this.logger.error("Failed to load rtp configuration module. Falling back to default settings.", e);
        }
        RtpConfig defaultConfig = RtpConfig.createDefault();
        this.activeConfig = defaultConfig;
        return defaultConfig;
    }
}
