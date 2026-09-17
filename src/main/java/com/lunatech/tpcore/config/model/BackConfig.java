package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record BackConfig(
    @Comment("Enable or disable the Back module completely")
    boolean enabled,

    @Comment("Warmup delay in seconds before returning to back location")
    int warmupSeconds,

    @Comment("Cooldown in seconds between back teleports")
    int cooldownSeconds,

    @Comment("Cancel teleport warmup if the player moves")
    boolean cancelOnMove,

    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Comment("Maximum depth of back history entries stored per player")
    int maxHistoryDepth,

    @Comment("Minimum distance (in blocks) required for a teleport to be recorded into back history")
    double minTeleportDistance,

    @Comment("Track standard command and plugin teleports")
    boolean trackTeleports,

    @Comment("Track player death locations")
    boolean trackDeaths,

    @Comment("Track portal teleports")
    boolean trackPortals,

    @Comment("Persist death locations to storage across server restarts")
    boolean persistDeathLocations,

    @Comment("Safety checks before returning to back location")
    BackSafetyConfig safetyChecks,

    @Comment("Persistence engine configuration ('SQLITE' or 'YAML')")
    BackStorageConfig storage,

    @Comment("Module message strings (Alphabetically ordered)")
    BackMessages messages
) {
    public static BackConfig createDefault() {
        return new BackConfig(
            true,
            3,
            10,
            true,
            true,
            5,
            10.0,
            true,
            true,
            false,
            true,
            BackSafetyConfig.createDefault(),
            BackStorageConfig.createDefault(),
            BackMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record BackSafetyConfig(
        boolean preventUnsafeTeleport,
        boolean autoAdjustHazard,
        int hazardSearchRadius,
        boolean preventNetherRoof,
        int maxNetherHeight
    ) {
        public static BackSafetyConfig createDefault() {
            return new BackSafetyConfig(true, true, 5, true, 128);
        }
    }

    @ConfigSerializable
    public record BackStorageConfig(
        String type
    ) {
        public static BackStorageConfig createDefault() {
            return new BackStorageConfig("SQLITE");
        }
    }

    @ConfigSerializable
    public record BackMessages(
        String backListCategoryHeader,
        String backListEmpty,
        String backListHeader,
        String backListItem,
        String cooldownActive,
        String historyCleared,
        String moduleDisabled,
        String noBackLocation,
        String noDeathLocation,
        String noPermission,
        String onlyPlayers,
        String prefix,
        String teleportAdjustedHazard,
        String teleportSuccess,
        String unsafeLocation,
        String warmupCancelledDamage,
        String warmupCancelledMove,
        String warmupStart,
        String worldNotLoaded
    ) {
        public static BackMessages createDefault() {
            return new BackMessages(
                "<prefix><gray>Location History for <gold><category></gold> (Page <gold><page></gold>/<gold><maxpages></gold>):</gray>",
                "<prefix><yellow>No previous locations found.</yellow>",
                "<prefix><gray>Your Location History (Page <gold><page></gold>/<gold><maxpages></gold>):</gray>",
                "  <dark_gray>•</dark_gray> <green><cause></green> <gray>in</gray> <yellow><world></yellow> <gray>(<x>, <y>, <z>)</gray> <click:run_command:'/back <index>'><hover:show_text:'<green>Click to teleport</green><br><gray>Time: <yellow><time></yellow>'><gold>[Teleport]</gold></hover></click>",
                "<prefix><red>You must wait <gold><seconds>s</gold> before using /back again!</red>",
                "<prefix><green>Your back location history has been cleared.</green>",
                "<prefix><red>Back module is currently disabled.</red>",
                "<prefix><red>You do not have a previous location to return to!</red>",
                "<prefix><red>You do not have a recorded death location!</red>",
                "<prefix><red>You do not have permission to access back location!</red>",
                "<prefix><red>Only players can execute this command!</red>",
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><yellow>Original location was dangerous (<hazard>). Safely adjusted target location!</yellow>",
                "<prefix><green>Teleported back to your previous location!</green>",
                "<prefix><red>Target back location is unsafe or obstructed!</red>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><gray>Teleporting back in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>",
                "<prefix><red>Target world <yellow><world></yellow> is not currently loaded!</red>"
            );
        }
    }
}
