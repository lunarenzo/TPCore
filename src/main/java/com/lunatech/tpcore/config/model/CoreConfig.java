package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record CoreConfig(
    @Comment("Universal prefix for plugin administrative and core messages")
    String prefix,

    @Comment("Enable debug logging for development and detailed diagnostics")
    boolean debug,

    @Comment("Universal administrative localization strings (Alphabetically ordered)")
    CoreMessages messages
) {
    public static CoreConfig createDefault() {
        return new CoreConfig(
            "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
            false,
            CoreMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record CoreMessages(
        String adminHelp,
        String migrateFail,
        String migrateInvalidEngine,
        String migrateNotSupported,
        String migrateSameEngine,
        String migrateSuccess,
        String onlyPlayers,
        String reloadAllComplete,
        String reloadCoreFail,
        String reloadCoreSuccess,
        String reloadModuleFail,
        String reloadModuleSuccess,
        String reloadNoModules,
        String reloadUnknownModule,
        String versionInfo
    ) {
        public static CoreMessages createDefault() {
            return new CoreMessages(
                "<prefix><gray>Admin Commands:\n <gold>/tpcore reload [all|core|module]</gold> <dark_gray>-</dark_gray> <gray>Reload configuration files</gray>\n <gold>/tpcore migrate <module> <from> <to></gold> <dark_gray>-</dark_gray> <gray>Migrate module data</gray>\n <gold>/tpcore version</gold> <dark_gray>-</dark_gray> <gray>Display plugin version & active modules</gray></gray>",
                "<prefix><red>Failed to migrate data for module <yellow><module></yellow> from <yellow><from></yellow> to <yellow><to></yellow>! Check server console for errors.</red>",
                "<prefix><red>Invalid storage engine '<yellow><engine></yellow>'. Valid engines: SQLITE, YAML.</red>",
                "<prefix><red>Module <yellow><module></yellow> does not support data migration.</red>",
                "<prefix><red>Source and target storage engines cannot be identical!</red>",
                "<prefix><green>Successfully migrated <gold><count></gold> record(s) for module <yellow><module></yellow> from <yellow><from></yellow> to <yellow><to></yellow>!</green>",
                "<prefix><red>Only players can execute this command!</red>",
                "<prefix><green>Reload complete. (<gold><success>/<total></gold> modules reloaded)</green>",
                "<prefix><red>Failed to reload root configuration (config.yml)! Check server console for errors.</red>",
                "<prefix><green>Root configuration (config.yml) reloaded successfully.</green>",
                "<prefix><gray>Module <yellow><module></yellow>: <red>Failed to reload! Check server console for errors.</red></gray>",
                "<prefix><gray>Module <yellow><module></yellow>: <green>Reloaded successfully.</green></gray>",
                "<prefix><yellow>No modules currently registered to reload.</yellow>",
                "<prefix><red>Unknown module: <yellow><module></yellow>. Registered: <gold><modules></gold></red>",
                "<prefix><green>TPCore Version: <gold>v<version></gold></green>\n<prefix><gray>Server Engine: <white>Minecraft <mcversion></white></gray>\n<prefix><gray>Active Modules: <gold><modules></gold></gray>"
            );
        }
    }
}
