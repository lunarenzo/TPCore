package com.lunatech.tpcore.config;

import com.lunatech.tpcore.config.model.CoreConfig;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        boolean extracted = this.extractResourceIfMissing("config.yml", file);

        try {
            CoreConfig result = this.loadAndMergeConfig(file, CoreConfig.class, CoreConfig.createDefault(), extracted, "config.yml");
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
        boolean extracted = false;
        if (!Files.exists(file)) {
            extracted = this.extractResourceIfMissing("config.yml", file);
        }

        try {
            CoreConfig newConfig = this.loadAndMergeConfig(file, CoreConfig.class, CoreConfig.createDefault(), extracted, "config.yml");
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

    public ReloadableModule getModule(String moduleName) {
        if (moduleName == null) {
            return null;
        }
        return this.registeredModules.get(moduleName.toLowerCase());
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
        String resourcePath = "modules/" + moduleName + ".yml";
        boolean extracted = this.extractResourceIfMissing(resourcePath, file);

        try {
            return this.loadAndMergeConfig(file, configClass, defaultConfig, extracted, resourcePath);
        } catch (ConfigurateException e) {
            this.logger.error("Error parsing module configuration {}.yml. Falling back to default settings.", moduleName, e);
        }

        return defaultConfig;
    }

    public <T> T tryLoadModuleConfig(String moduleName, Class<T> configClass, T defaultConfig) throws ConfigurateException {
        Path file = this.modulesDirectory.resolve(moduleName + ".yml");
        String resourcePath = "modules/" + moduleName + ".yml";
        boolean extracted = false;
        if (!Files.exists(file)) {
            extracted = this.extractResourceIfMissing(resourcePath, file);
        }
        if (!Files.exists(file)) {
            return defaultConfig;
        }

        return this.loadAndMergeConfig(file, configClass, defaultConfig, extracted, resourcePath);
    }

    private <T> T loadAndMergeConfig(Path file, Class<T> configClass, T defaultConfig, boolean newlyExtracted, String resourcePath) throws ConfigurateException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(file)
            .nodeStyle(NodeStyle.BLOCK)
            .build();

        CommentedConfigurationNode rootNode = loader.load();
        CommentedConfigurationNode defaultNode = CommentedConfigurationNode.root();
        defaultNode.set(configClass, defaultConfig);

        if (!newlyExtracted && resourcePath != null && hasMissingKeys(rootNode, defaultNode)) {
            appendMissingKeysFromResource(file, resourcePath, rootNode, defaultNode);
            rootNode = loader.load();
        }

        rootNode.mergeFrom(defaultNode);

        T result = rootNode.get(configClass);
        return (result != null) ? result : defaultConfig;
    }

    private void appendMissingKeysFromResource(Path file, String resourcePath, CommentedConfigurationNode rootNode, CommentedConfigurationNode defaultNode) {
        if (resourcePath == null || !Files.exists(file)) {
            return;
        }

        Set<String> missingPaths = getMissingPaths(rootNode, defaultNode, "");
        if (missingPaths.isEmpty()) {
            return;
        }

        try (InputStream in = this.classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }

            List<String> resourceLines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList();
            StringBuilder toAppend = new StringBuilder();
            List<String> pendingComments = new ArrayList<>();

            String currentSection = "";
            boolean sectionIsMissing = false;

            for (String line : resourceLines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    pendingComments.add(line);
                    continue;
                }

                if (!line.startsWith(" ")) {
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String key = line.substring(0, colonIndex).trim();
                        currentSection = key;
                        boolean keyMissing = missingPaths.contains(key);
                        sectionIsMissing = keyMissing;

                        if (keyMissing || hasMissingChildPath(missingPaths, key)) {
                            if (keyMissing) {
                                for (String comment : pendingComments) {
                                    toAppend.append(comment).append("\n");
                                }
                                toAppend.append(line).append("\n");
                            }
                        }
                    }
                    pendingComments.clear();
                } else if (line.startsWith("  ") && !line.startsWith("   ")) {
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String childKey = line.substring(2, colonIndex).trim();
                        String fullPath = currentSection.isEmpty() ? childKey : currentSection + "." + childKey;
                        if (missingPaths.contains(fullPath) || sectionIsMissing) {
                            for (String comment : pendingComments) {
                                toAppend.append(comment).append("\n");
                            }
                            toAppend.append(line).append("\n");
                        }
                    }
                    pendingComments.clear();
                } else {
                    if (sectionIsMissing) {
                        toAppend.append(line).append("\n");
                    }
                    pendingComments.clear();
                }
            }

            if (toAppend.length() > 0) {
                String contentToAppend = (Files.size(file) > 0 ? "\n" : "") + toAppend.toString();
                Files.writeString(file, contentToAppend, StandardOpenOption.APPEND);
                this.logger.info("Auto-appended missing configuration keys and comments to {}", file.getFileName());
            }
        } catch (Exception e) {
            this.logger.error("Failed to auto-append missing configuration keys to {}", file.getFileName(), e);
        }
    }

    private Set<String> getMissingPaths(CommentedConfigurationNode current, CommentedConfigurationNode defaults, String currentPath) {
        Set<String> missing = new LinkedHashSet<>();
        if (defaults.isMap()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : defaults.childrenMap().entrySet()) {
                String keyStr = String.valueOf(entry.getKey());
                String fullPath = currentPath.isEmpty() ? keyStr : currentPath + "." + keyStr;
                CommentedConfigurationNode childCurrent = current.node(entry.getKey());
                if (childCurrent.virtual() || childCurrent.raw() == null) {
                    missing.add(fullPath);
                } else if (entry.getValue().isMap()) {
                    missing.addAll(getMissingPaths(childCurrent, entry.getValue(), fullPath));
                }
            }
        }
        return missing;
    }

    private boolean hasMissingChildPath(Set<String> missingPaths, String sectionKey) {
        String prefix = sectionKey + ".";
        for (String path : missingPaths) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMissingKeys(CommentedConfigurationNode current, CommentedConfigurationNode defaults) {
        if (defaults.isMap()) {
            for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : defaults.childrenMap().entrySet()) {
                Object key = entry.getKey();
                CommentedConfigurationNode childCurrent = current.node(key);
                if (childCurrent.virtual() || childCurrent.raw() == null) {
                    return true;
                }
                if (entry.getValue().isMap() && hasMissingKeys(childCurrent, entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean extractResourceIfMissing(String resourcePath, Path targetPath) {
        if (!Files.exists(targetPath)) {
            try (InputStream in = this.classLoader.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    this.logger.info("Extracted default configuration resource to {}", targetPath.getFileName());
                    return true;
                }
            } catch (Exception e) {
                this.logger.error("Failed to extract default configuration resource: {}", resourcePath, e);
            }
        }
        return false;
    }
}
