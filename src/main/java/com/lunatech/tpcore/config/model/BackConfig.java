package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;

@ConfigSerializable
public record BackConfig(
    @Setting("enabled")
    @Comment("Enable or disable the Back module completely")
    boolean enabled,

    @Setting("warmup-seconds")
    @Comment("Warmup delay in seconds before returning to back location")
    int warmupSeconds,

    @Setting("cooldown-seconds")
    @Comment("Cooldown in seconds between back teleports")
    int cooldownSeconds,

    @Setting("cancel-on-move")
    @Comment("Cancel teleport warmup if the player moves")
    boolean cancelOnMove,

    @Setting("cancel-on-damage")
    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Setting("max-history-depth")
    @Comment("Maximum depth of back history entries stored per player")
    int maxHistoryDepth,

    @Setting("min-teleport-distance")
    @Comment("Minimum distance (in blocks) required for a teleport to be recorded into back history")
    double minTeleportDistance,

    @Setting("track-teleports")
    @Comment("Track standard command and plugin teleports")
    boolean trackTeleports,

    @Setting("track-deaths")
    @Comment("Track player death locations")
    boolean trackDeaths,

    @Setting("track-portals")
    @Comment("Track portal teleports")
    boolean trackPortals,

    @Setting("persist-death-locations")
    @Comment("Persist death locations to storage across server restarts")
    boolean persistDeathLocations,

    @Setting("safety-checks")
    @Comment("Safety checks before returning to back location")
    BackSafetyConfig safetyChecks,

    @Setting("storage")
    @Comment("Persistence engine configuration ('SQLITE' or 'YAML')")
    BackStorageConfig storage,

    @Setting("messages")
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
        @Setting("prevent-unsafe-teleport")
        boolean preventUnsafeTeleport,

        @Setting("auto-adjust-hazard")
        boolean autoAdjustHazard,

        @Setting("hazard-search-radius")
        int hazardSearchRadius,

        @Setting("prevent-nether-roof")
        boolean preventNetherRoof,

        @Setting("max-nether-height")
        int maxNetherHeight
    ) {
        public static BackSafetyConfig createDefault() {
            return new BackSafetyConfig(true, true, 5, true, 128);
        }
    }

    @ConfigSerializable
    public record BackStorageConfig(
        @Setting("type")
        String type
    ) {
        public static BackStorageConfig createDefault() {
            return new BackStorageConfig("SQLITE");
        }
    }

    @ConfigSerializable
    public record BackMessages(
        @Setting("back-list-category-header")
        String backListCategoryHeader,

        @Setting("back-list-empty")
        String backListEmpty,

        @Setting("back-list-header")
        String backListHeader,

        @Setting("back-list-item")
        String backListItem,

        @Setting("cooldown-active")
        String cooldownActive,

        @Setting("history-cleared")
        String historyCleared,

        @Setting("module-disabled")
        String moduleDisabled,

        @Setting("no-back-location")
        String noBackLocation,

        @Setting("no-death-location")
        String noDeathLocation,

        @Setting("no-permission")
        String noPermission,

        @Setting("only-players")
        String onlyPlayers,

        @Setting("prefix")
        String prefix,

        @Setting("teleport-adjusted-hazard")
        String teleportAdjustedHazard,

        @Setting("teleport-success")
        String teleportSuccess,

        @Setting("unsafe-location")
        String unsafeLocation,

        @Setting("warmup-cancelled-damage")
        String warmupCancelledDamage,

        @Setting("warmup-cancelled-move")
        String warmupCancelledMove,

        @Setting("warmup-start")
        String warmupStart,

        @Setting("world-not-loaded")
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
