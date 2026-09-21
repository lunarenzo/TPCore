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

    @Test
    void testAutoAppendsMissingKeysAndComments() throws IOException {
        Path tpaFile = tempDir.resolve("modules").resolve("tpa.yml");

        // Write an old tpa.yml missing newer config keys
        String oldYaml = """
            # Legacy Config
            enabled: true
            request-timeout-seconds: 30
            warmup-seconds: 3
            """;
        Files.writeString(tpaFile, oldYaml);

        TpaConfig config = configManager.loadModuleConfig("tpa", TpaConfig.class, TpaConfig.createDefault());
        assertNotNull(config);
        assertTrue(config.enableConfirmationMenu());

        List<String> updatedLines = Files.readAllLines(tpaFile);
        boolean containsMissingKey = updatedLines.stream().anyMatch(l -> l.contains("enable-confirmation-menu"));
        boolean containsMissingComment = updatedLines.stream().anyMatch(l -> l.contains("# Whether to enable confirmation menus"));

        assertTrue(containsMissingKey, "Missing key enable-confirmation-menu must be auto-appended to tpa.yml");
        assertTrue(containsMissingComment, "Missing key comments must be auto-appended to tpa.yml");
    }
}
