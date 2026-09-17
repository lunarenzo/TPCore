package com.lunatech.tpcore.config;

import com.lunatech.tpcore.config.model.CoreConfig;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ModularConfigManager {

    private final Path dataDirectory;
    private final Path modulesDirectory;
    private final Logger logger;
    private final ClassLoader classLoader;

    private final AtomicReference<CoreConfig> coreConfigRef = new AtomicReference<>(CoreConfig.createDefault());
    private final Map<String, ReloadableModule> registeredModules = new ConcurrentHashMap<>();
    private final AtomicBoolean reloading = new AtomicBoolean(false);

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

    public boolean tryLockReload() {
        return this.reloading.compareAndSet(false, true);
    }

    public void unlockReload() {
        this.reloading.set(false);
    }

    public boolean isReloading() {
        return this.reloading.get();
    }

    public CoreConfig getCoreConfig() {
        return this.coreConfigRef.get();
    }

    public CoreConfig loadCoreConfig() {
        Path file = this.dataDirectory.resolve("config.yml");
        this.extractResourceIfMissing("config.yml", file);

        try {
            CoreConfig result = this.loadAndMergeConfig(file, CoreConfig.class, CoreConfig.createDefault());
            this.coreConfigRef.set(result);
            return result;
        } catch (ConfigurateException e) {
            this.logger.error("Error parsing root configuration config.yml. Falling back to default settings.", e);
        }

        CoreConfig defaultConfig = CoreConfig.createDefault();
        this.coreConfigRef.set(defaultConfig);
        return defaultConfig;
    }

    public boolean reloadCoreConfig() {
        Path file = this.dataDirectory.resolve("config.yml");
        if (!Files.exists(file)) {
            this.extractResourceIfMissing("config.yml", file);
        }

        try {
            CoreConfig newConfig = this.loadAndMergeConfig(file, CoreConfig.class, CoreConfig.createDefault());
            this.coreConfigRef.set(newConfig);
            return true;
        } catch (ConfigurateException e) {
            this.logger.error("Failed to reload root configuration config.yml. Retaining previous configuration.", e);
        }

        return false;
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
        if (moduleName.equalsIgnoreCase("core")) {
            return reloadCoreConfig();
        }
        ReloadableModule module = this.registeredModules.get(moduleName.toLowerCase());
        if (module == null) {
            return false;
        }
        return module.reloadConfig();
    }

    public Map<String, Boolean> reloadAllModules() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        results.put("core", reloadCoreConfig());

        for (Map.Entry<String, ReloadableModule> entry : this.registeredModules.entrySet()) {
            boolean success = entry.getValue().reloadConfig();
            results.put(entry.getKey(), success);
        }
        return results;
    }

    public <T> T loadModuleConfig(String moduleName, Class<T> configClass, T defaultConfig) {
        Path file = this.modulesDirectory.resolve(moduleName + ".yml");
        this.extractResourceIfMissing("modules/" + moduleName + ".yml", file);

        try {
            return this.loadAndMergeConfig(file, configClass, defaultConfig);
        } catch (ConfigurateException e) {
            this.logger.error("Error parsing module configuration {}.yml. Falling back to default settings.", moduleName, e);
        }

        return defaultConfig;
    }

    public <T> T tryLoadModuleConfig(String moduleName, Class<T> configClass, T defaultConfig) throws ConfigurateException {
        Path file = this.modulesDirectory.resolve(moduleName + ".yml");
        if (!Files.exists(file)) {
            this.extractResourceIfMissing("modules/" + moduleName + ".yml", file);
        }
        if (!Files.exists(file)) {
            return defaultConfig;
        }

        return this.loadAndMergeConfig(file, configClass, defaultConfig);
    }

    private <T> T loadAndMergeConfig(Path file, Class<T> configClass, T defaultConfig) throws ConfigurateException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();

        CommentedConfigurationNode rootNode = loader.load();
        CommentedConfigurationNode defaultNode = CommentedConfigurationNode.root();
        defaultNode.set(configClass, defaultConfig);

        defaultNode.mergeFrom(rootNode);
        loader.save(defaultNode);

        T result = defaultNode.get(configClass);
        return (result != null) ? result : defaultConfig;
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
