package com.lunatech.tpcore.config;

import com.lunatech.tpcore.config.model.TpaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModularConfigManagerTest {

    @TempDir
    Path tempDir;

    private ModularConfigManager configManager;

    @BeforeEach
    void setUp() {
        this.configManager = new ModularConfigManager(
            tempDir,
            LoggerFactory.getLogger("TestLogger"),
            getClass().getClassLoader()
        );
        this.configManager.initializeDirectories();
    }

    @Test
    void testModuleConfigExtractionPreservesComments() throws IOException {
        Path tpaFile = tempDir.resolve("modules").resolve("tpa.yml");
        assertFalse(Files.exists(tpaFile), "tpa.yml should not exist prior to loading");

        TpaConfig loadedConfig = configManager.loadModuleConfig("tpa", TpaConfig.class, TpaConfig.createDefault());
        assertNotNull(loadedConfig);
        assertTrue(Files.exists(tpaFile), "tpa.yml should be extracted to disk");

        List<String> lines = Files.readAllLines(tpaFile);
        boolean containsHeaderComment = lines.stream().anyMatch(l -> l.contains("# TPCore"));
        boolean containsFieldComment = lines.stream().anyMatch(l -> l.contains("# Warmup delay in seconds"));

        assertTrue(containsHeaderComment, "Extracted tpa.yml must contain header comments");
        assertTrue(containsFieldComment, "Extracted tpa.yml must contain field comments");
    }

    @Test
    void testReLoadingModuleConfigDoesNotOverwriteFileOrStripComments() throws IOException {
        Path tpaFile = tempDir.resolve("modules").resolve("tpa.yml");

        configManager.loadModuleConfig("tpa", TpaConfig.class, TpaConfig.createDefault());
        assertTrue(Files.exists(tpaFile));

        long lastModified = Files.getLastModifiedTime(tpaFile).toMillis();

        // Reload module config
        TpaConfig reloaded = configManager.loadModuleConfig("tpa", TpaConfig.class, TpaConfig.createDefault());
        assertNotNull(reloaded);

        List<String> lines = Files.readAllLines(tpaFile);
        boolean containsHeaderComment = lines.stream().anyMatch(l -> l.contains("# TPCore"));
        assertTrue(containsHeaderComment, "Reloaded tpa.yml must still retain comments");
    }
}
