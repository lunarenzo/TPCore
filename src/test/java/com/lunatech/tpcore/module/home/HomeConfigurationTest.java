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
        assertTrue(fresh.gui().enabled());
        assertEquals(60, fresh.gui().promptTimeoutSeconds());
        assertTrue(fresh.gui().bedrock().enabled());
        assertEquals("Create Home", fresh.gui().bedrock().message("title"));
        assertEquals("RED_BED", fresh.gui().icons().get("set").material());
        var legacy = "enabled: false\nwarmup-seconds: 9\nruntime:\n  recovery-capacity: 7\n";
        Files.writeString(path, legacy);
        var existing = configManager.loadModuleConfig("home", HomeConfig.class, HomeConfig.createDefault(), false);
        assertEquals(legacy, Files.readString(path));
        assertFalse(existing.enabled());
        assertEquals(9, existing.warmupSeconds());
        assertEquals(7, existing.runtime().recoveryCapacity());
        assertEquals(64, existing.runtime().storageQueueCapacity());
        assertTrue(existing.gui().enabled());
        assertTrue(existing.gui().bedrock().enabled());
        Files.writeString(path, legacy + "gui:\n  enabled: false\n  icons:\n    home:\n      name: My custom home\n");
        var customized = configManager.loadModuleConfig("home", HomeConfig.class, HomeConfig.createDefault(), false);
        assertFalse(customized.gui().enabled());
        assertEquals("My custom home", customized.gui().icons().get("home").name());
        assertEquals("CYAN_BED", customized.gui().icons().get("home").material());
        assertEquals(60, customized.gui().promptTimeoutSeconds());
    }
}
