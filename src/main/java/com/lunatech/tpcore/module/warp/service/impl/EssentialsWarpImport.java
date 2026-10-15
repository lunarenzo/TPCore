package com.lunatech.tpcore.module.warp.service.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpImportConfig;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpImportPlan;
import com.lunatech.tpcore.module.warp.model.WarpImportPlan.Entry;
import com.lunatech.tpcore.module.warp.model.WarpImportPlan.WorldData;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Pure filesystem work: callers supply immutable world metadata, never live server objects. */
public final class EssentialsWarpImport {
    public record Source(Map<String, byte[]> files, Map<String, String> fingerprints) {}

    public Source read(Path pluginFolder, WarpImportConfig config) {
        try {
            Path plugins = pluginFolder.toAbsolutePath().getParent().toRealPath();
            Path source = plugins.resolve(config.sourceFolder()).normalize();
            if (!source.startsWith(plugins) || source.equals(plugins) || !source.toRealPath().startsWith(plugins)) throw failure("SOURCE");
            List<Path> paths = new ArrayList<>();
            try (var files = Files.newDirectoryStream(source, "*.yml")) {
                for (Path file : files) {
                    if (paths.size() >= config.maxFiles()) throw failure("LIMIT");
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(source.toRealPath())) throw failure("SOURCE");
                    paths.add(file);
                }
            }
            paths.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            Map<String, byte[]> files = new HashMap<>(); Map<String, String> hashes = new HashMap<>(); long total = 0;
            for (Path path : paths) {
                byte[] bytes;
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(config.maxFileBytes() + 1); }
                total += bytes.length;
                if (bytes.length > config.maxFileBytes() || total > config.maxTotalBytes()) throw failure("LIMIT");
                String name = path.getFileName().toString(); files.put(name, bytes);
                hashes.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            return new Source(Map.copyOf(files), Map.copyOf(hashes));
        } catch (IllegalStateException failure) { throw failure; }
        catch (Exception failure) { throw failure("SOURCE", failure); }
    }

    public WarpImportPlan plan(Source source, Map<String, Warp> destination, Map<String, WorldData> worlds, WarpConfig settings, String policy) {
        if (!Set.of("skip", "replace", "rename").contains(policy)) throw failure("STALE");
        List<Entry> entries = new ArrayList<>(); List<Warp> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>(), reserved = new HashSet<>(destination.keySet());
        int conflicts = 0, invalid = 0; long created = System.currentTimeMillis();
        for (String file : source.files().keySet().stream().sorted().toList()) {
            String name = file, target = "";
            try {
                byte[] bytes = source.files().get(file);
                validateFlatYaml(new String(bytes, StandardCharsets.UTF_8));
                ConfigurationNode node = YamlConfigurationLoader.builder().source(() -> new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))).build().load();
                name = node.node("name").getString("");
                if (!name.matches("[a-zA-Z0-9_-]{1,32}")) throw failure("INVALID_NAME");
                if (!seen.add(name.toLowerCase(Locale.ROOT))) { entries.add(new Entry(file, name, "", "DUPLICATE")); invalid++; continue; }
                String worldValue = node.node("world").getString("");
                WorldData world = null;
                try { world = worlds.get("id:" + UUID.fromString(worldValue)); } catch (IllegalArgumentException ignored) { /* Legacy files store a world name. */ }
                if (world == null) world = worlds.get("name:" + worldValue.toLowerCase(Locale.ROOT));
                if (world == null && !worldValue.isBlank()) world = worlds.get("name:" + node.node("world-name").getString("").toLowerCase(Locale.ROOT));
                if (world == null) throw failure("INVALID_WORLD");
                double x = number(node, "x", false), y = number(node, "y", false), z = number(node, "z", false);
                double yaw = number(node, "yaw", true), pitch = number(node, "pitch", true);
                if (x < Integer.MIN_VALUE || x >= Integer.MAX_VALUE || z < Integer.MIN_VALUE || z >= Integer.MAX_VALUE
                    || y < world.minHeight() || y >= world.maxHeight() || Math.abs(yaw) > Float.MAX_VALUE || pitch < -90 || pitch > 90) throw failure("INVALID_LOCATION");
                target = name; String reason = "NEW"; Warp existing = destination.get(name.toLowerCase(Locale.ROOT));
                if (existing != null) {
                    conflicts++;
                    if (policy.equals("skip")) { entries.add(new Entry(file, name, name, "SKIP")); continue; }
                    if (policy.equals("replace")) { target = existing.name(); reason = "REPLACE"; }
                    else { target = renamed(name, reserved); reason = "RENAME"; }
                } else if (reserved.contains(target.toLowerCase(Locale.ROOT))) { target = renamed(name, reserved); reason = "RENAME"; }
                reserved.add(target.toLowerCase(Locale.ROOT));
                UUID owner = null;
                try { owner = UUID.fromString(node.node("lastowner").getString("")); } catch (IllegalArgumentException ignored) { /* EssentialsX likewise treats an invalid owner as absent. */ }
                Warp warp = existing != null && policy.equals("replace")
                    ? new Warp(existing.name(), world.id(), world.name(), x, y, z, (float) yaw, (float) pitch, existing.creatorUuid(), existing.category(), existing.passwordHash(), existing.permissionGated(), existing.createdAt(), existing.closed(), existing.closureReason(), existing.unlisted())
                    : new Warp(target, world.id(), world.name(), x, y, z, (float) yaw, (float) pitch, owner, settings.essentialsImport().category(), null, settings.essentialsImport().permissionRequired(), created);
                selected.add(warp); entries.add(new Entry(file, name, target, reason));
            } catch (Exception failure) {
                String reason = failure instanceof IllegalStateException && Set.of("INVALID_NAME", "INVALID_WORLD", "INVALID_LOCATION").contains(failure.getMessage()) ? failure.getMessage() : "INVALID_FILE";
                entries.add(new Entry(file, name.substring(0, Math.min(name.length(), 32)), target, reason)); invalid++;
            }
        }
        int limit = settings.maxWarps() > 0 ? Math.min(settings.maxWarps(), settings.maxCachedWarps()) : settings.maxCachedWarps();
        if (reserved.size() > limit) throw failure("LIMIT");
        return new WarpImportPlan(UUID.randomUUID(), policy, source.fingerprints(), destination, selected, entries, conflicts, invalid);
    }

    private static double number(ConfigurationNode node, String key, boolean optional) {
        Object raw = node.node(key).raw();
        if (raw == null && optional) return 0;
        if (!(raw instanceof Number value) || !Double.isFinite(value.doubleValue())) throw failure("INVALID_LOCATION");
        return value.doubleValue();
    }
    /** EssentialsX emits a flat scalar mapping. Reject YAML object tags and alias graphs before parsing. */
    private static void validateFlatYaml(String text) {
        Set<String> keys = new HashSet<>();
        boolean document = false;
        for (String original : (text.startsWith("\ufeff") ? text.substring(1) : text).split("\\R")) {
            String line = original.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("---") && keys.isEmpty() && !document) { document = true; continue; }
            int colon = line.indexOf(':');
            if (colon < 0) throw failure("INVALID_FILE");
            String key = line.substring(0, colon);
            if (!Set.of("name", "world", "world-name", "x", "y", "z", "yaw", "pitch", "lastowner").contains(key) || !keys.add(key)) throw failure("INVALID_FILE");
            String value = line.substring(colon + 1).strip();
            if (value.startsWith("'") || value.startsWith("\"")) validateQuotedScalar(value);
            else if (!value.isEmpty() && "!&*|>[{".indexOf(value.charAt(0)) >= 0) throw failure("INVALID_FILE");
        }
    }
    private static void validateQuotedScalar(String value) {
        char quote = value.charAt(0);
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote == '"' && character == '\\') { index++; continue; }
            if (character != quote) continue;
            if (quote == '\'' && index + 1 < value.length() && value.charAt(index + 1) == quote) { index++; continue; }
            String remaining = value.substring(index + 1);
            if (!remaining.isBlank() && (!Character.isWhitespace(remaining.charAt(0)) || !remaining.stripLeading().startsWith("#"))) throw failure("INVALID_FILE");
            return;
        }
        throw failure("INVALID_FILE");
    }
    private static String renamed(String name, Set<String> reserved) {
        for (int suffix = 1; suffix <= reserved.size() + 1; suffix++) {
            String ending = "_" + suffix; String candidate = name.substring(0, Math.min(name.length(), 32 - ending.length())) + ending;
            if (!reserved.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        throw failure("LIMIT");
    }

    /** Runs only on the import I/O lane. Backup must complete and verify before returning. */
    public String backup(Path folder, Source source, WarpImportPlan plan, List<DeletedWarp> history, WarpConfig settings, Logger logger) {
        try {
            Path parent = folder.resolve("warp-import-backups");
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent)) throw failure("IO");
            int count = 0;
            try (var backups = Files.newDirectoryStream(parent)) {
                for (Path ignored : backups) if (++count >= settings.essentialsImport().maxBackups()) throw failure("BACKUP_LIMIT");
            }
            Path backup = parent.resolve(plan.token().toString()); Files.createDirectory(backup);
            Path originals = backup.resolve("essentials"); Files.createDirectory(originals);
            for (var file : source.files().entrySet()) Files.write(originals.resolve(file.getKey()), file.getValue());
            YamlWarpRepository snapshot = new YamlWarpRepository(backup.toFile(), logger, 1, settings.maxCachedWarps(), settings.maxStorageBytes(), settings.control());
            try {
                snapshot.initialize().join(); snapshot.saveAll(plan.destination().values()).join(); snapshot.saveDeleted(history).join();
                if (!snapshot.loadAll().join().equals(plan.destination())) throw failure("IO");
            } finally { snapshot.close().join(); }
            Files.writeString(backup.resolve("manifest.txt"), "EssentialsX warp import\npolicy=" + plan.policy() + "\nselected=" + plan.selected().size()
                + "\nsource-files=" + source.files().size() + "\n" + plan.entries(), StandardCharsets.UTF_8);
            return backup.toString();
        } catch (IllegalStateException failure) { throw failure; }
        catch (Exception failure) { throw failure("IO", failure); }
    }
    public static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private static IllegalStateException failure(String code, Throwable cause) { return new IllegalStateException(code, cause); }
}
