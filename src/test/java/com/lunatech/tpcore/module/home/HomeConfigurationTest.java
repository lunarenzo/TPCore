package com.lunatech.tpcore.module.home;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.model.HomeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class HomeConfigurationTest {
    @TempDir Path directory;

    @Test
    void extractsTemplateAndLoadsExistingPartialConfigWithoutRewritingIt() throws Exception {
        if (Boolean.getBoolean("home.minJar")) {
            assertTrue(HomeConfig.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("-min.jar"));
        }
        var configManager = new ModularConfigManager(directory, LoggerFactory.getLogger(getClass()), HomeConfig.class.getClassLoader());
        configManager.initializeDirectories();
        var path = directory.resolve("modules/home.yml");
        var fresh = configManager.loadModuleConfig("home", HomeConfig.class, HomeConfig.createDefault(), false);
        assertTrue(Files.exists(path));
        assertTrue(Files.readString(path).contains("generate-chunks: false"));
        assertFalse(fresh.runtime().generateChunks());
        assertEquals(64, fresh.runtime().storageQueueCapacity());
        var legacy = "enabled: false\nwarmup-seconds: 9\nruntime:\n  recovery-capacity: 7\n";
        Files.writeString(path, legacy);
        var existing = configManager.loadModuleConfig("home", HomeConfig.class, HomeConfig.createDefault(), false);
        assertEquals(legacy, Files.readString(path));
        assertFalse(existing.enabled());
        assertEquals(9, existing.warmupSeconds());
        assertEquals(7, existing.runtime().recoveryCapacity());
        assertEquals(64, existing.runtime().storageQueueCapacity());
    }
}
