package com.lunatech.tpcore.config;

import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ModularConfigManager {

    private final Path dataDirectory;
    private final Path modulesDirectory;
    private final Logger logger;
    private final ClassLoader classLoader;

    private final Map<String, ReloadableModule> registeredModules = new ConcurrentHashMap<>();

    public ModularConfigManager(Path dataDirectory, Logger logger, ClassLoader classLoader) {
        this.dataDirectory = dataDirectory;
        this.modulesDirectory = dataDirectory.resolve("modules");
        this.logger = logger;
        this.classLoader = classLoader;
    }

    public void initializeDirectories() {
        try {
            if (!Files.exists(this.dataDirectory)) {
                Files.createDirectories(this.dataDirectory);
            }
            if (!Files.exists(this.modulesDirectory)) {
                Files.createDirectories(this.modulesDirectory);
            }
        } catch (Exception e) {
            this.logger.error("Failed to create TPCore configuration directories.", e);
        }
    }

    public void registerModule(ReloadableModule module) {
        if (module != null) {
            this.registeredModules.put(module.getModuleName().toLowerCase(), module);
        }
    }

    public void unregisterModule(String moduleName) {
        if (moduleName != null) {
            this.registeredModules.remove(moduleName.toLowerCase());
        }
    }

    public Set<String> getRegisteredModuleNames() {
        return Collections.unmodifiableSet(this.registeredModules.keySet());
    }

    public boolean reloadModule(String moduleName) {
        if (moduleName == null) {
            return false;
        }
        ReloadableModule module = this.registeredModules.get(moduleName.toLowerCase());
        if (module == null) {
            return false;
        }
        return module.reloadConfig();
    }

    public Map<String, Boolean> reloadAllModules() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Map.Entry<String, ReloadableModule> entry : this.registeredModules.entrySet()) {
            boolean success = entry.getValue().reloadConfig();
            results.put(entry.getKey(), success);
        }
        return results;
    }

    public <T> T loadModuleConfig(String moduleName, Class<T> configClass, T defaultConfig) {
        Path file = this.modulesDirectory.resolve(moduleName + ".yml");
        this.extractResourceIfMissing("modules/" + moduleName + ".yml", file);

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();

        try {
            CommentedConfigurationNode root = loader.load();
            T result = root.get(configClass);
            if (result != null) {
                return result;
            }
        } catch (ConfigurateException e) {
            this.logger.error("Error parsing module configuration {}.yml. Falling back to default settings.", moduleName, e);
        }

        return defaultConfig;
    }

    public <T> T tryLoadModuleConfig(String moduleName, Class<T> configClass) throws ConfigurateException {
        Path file = this.modulesDirectory.resolve(moduleName + ".yml");
        if (!Files.exists(file)) {
            return null;
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();

        CommentedConfigurationNode root = loader.load();
        return root.get(configClass);
    }

    private void extractResourceIfMissing(String resourcePath, Path targetPath) {
        if (!Files.exists(targetPath)) {
            try (InputStream in = this.classLoader.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    this.logger.info("Extracted default configuration resource to {}", targetPath.getFileName());
                }
            } catch (Exception e) {
                this.logger.error("Failed to extract default configuration resource: {}", resourcePath, e);
            }
        }
    }
}
