package com.lunatech.tpcore.config.model;

import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record WarpImportConfig(String sourceFolder, int maxFiles, int maxFileBytes, long maxTotalBytes,
    int previewSeconds, int previewLines, int maxBackups, String category, boolean permissionRequired, Messages messages) {
    public WarpImportConfig {
        if (sourceFolder == null || sourceFolder.isBlank()) sourceFolder = "Essentials/warps";
        maxFiles = Math.max(1, Math.min(4096, maxFiles));
        maxFileBytes = Math.max(256, Math.min(1048576, maxFileBytes));
        maxTotalBytes = Math.max(256, Math.min(16777216, maxTotalBytes));
        previewSeconds = Math.max(1, Math.min(600, previewSeconds));
        previewLines = Math.max(1, Math.min(100, previewLines));
        maxBackups = Math.max(1, Math.min(20, maxBackups));
        if (category == null || category.isBlank()) category = "imported";
        if (messages == null) messages = Messages.createDefault();
    }
    public static WarpImportConfig createDefault() {
        return new WarpImportConfig("Essentials/warps", 1024, 65536, 4194304, 120, 20, 5, "imported", true, Messages.createDefault());
    }
    @ConfigSerializable
    public record Messages(String description, String usage, String preview, String entry, String confirmation,
        String success, String failure, String omitted, String permissionNotice, Map<String, String> reasons) {
        public Messages {
            reasons = reasons == null ? defaults() : Map.copyOf(reasons);
        }
        private static Map<String, String> defaults() {
            return Map.ofEntries(
                Map.entry("NEW", "Create"), Map.entry("REPLACE", "Replace destination; preserve TPCore access settings"),
                Map.entry("RENAME", "Create under a new name"), Map.entry("SKIP", "Keep existing warp"),
                Map.entry("INVALID_FILE", "Invalid YAML or fields"), Map.entry("INVALID_NAME", "Invalid name (use 1-32 letters, digits, _ or -)"),
                Map.entry("INVALID_WORLD", "World is not loaded or cannot be resolved"), Map.entry("INVALID_LOCATION", "Invalid coordinates or rotation"),
                Map.entry("DUPLICATE", "Duplicate source name; skipped"), Map.entry("BUSY", "Warp storage or another import is busy"),
                Map.entry("NOT_READY", "Warp module or storage is unavailable"), Map.entry("STALE", "Preview expired or source, destination, worlds or configuration changed; preview again"),
                Map.entry("LIMIT", "Import exceeds configured file, byte or warp limits"), Map.entry("SOURCE", "Source folder is missing or is outside the plugins directory"),
                Map.entry("BACKUP_LIMIT", "Backup limit reached; archive old warp-import-backups directories before retrying"),
                Map.entry("IO", "I/O failed; no successful import is reported. Check the server log and any backup"),
                Map.entry("EMPTY", "Preview contains no importable warps"));
        }
        public static Messages createDefault() {
            return new Messages("Preview and import EssentialsX server warps",
                "<prefix><yellow>/warpimport essentials preview [skip|replace|rename]; /warpimport essentials confirm <token></yellow>",
                "<prefix><gold>EssentialsX preview: <files> files; <count> selected; <conflicts> conflicts; <invalid> invalid; policy <policy>.</gold>",
                "<gray><file>: <name> → <target> — <reason></gray>",
                "<prefix><yellow>Run /warpimport essentials confirm <token> within <seconds>s. A newer preview replaces this one.</yellow>",
                "<prefix><green>Imported <count> warps. Backup: <backup></green>",
                "<prefix><red>Import: <reason></red>", "<prefix><gray><count> more entries omitted from chat.</gray>",
                "<prefix><yellow>New imported warps require permission: <required>. EssentialsX permission grants, signs and costs are not imported.</yellow>", defaults());
        }
    }
}
