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

    @Setting("protection-seconds")
    @Comment("Invulnerability protection duration in seconds after a successful /back teleport (0 to disable)")
    int protectionSeconds,

    @Setting("protection-cancel-on-attack")
    @Comment("Cancel protection if the protected player attacks another player or mob")
    boolean protectionCancelOnAttack,

    @Setting("protection-all-damage")
    @Comment("Protect against all damage (PvE, environmental, fall, fire) or only PvP attacks")
    boolean protectionAllDamage,

    @Setting("cancel-on-move")
    @Comment("Cancel teleport warmup if the player moves")
    boolean cancelOnMove,

    @Setting("cancel-on-damage")
    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Setting("enable-bossbar")
    @Comment("Enable BossBar countdown for back warmup")
    boolean enableBossbar,

    @Setting("bossbar-color")
    @Comment("BossBar color (PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE)")
    String bossbarColor,

    @Setting("bossbar-overlay")
    @Comment("BossBar overlay style (PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20)")
    String bossbarOverlay,

    @Setting("bossbar-format")
    @Comment("BossBar title format with MiniMessage and <seconds> placeholder")
    String bossbarFormat,

    @Setting("enable-action-bar")
    @Comment("Enable action bar countdown during back warmup")
    boolean enableActionBar,

    @Setting("action-bar-format")
    @Comment("Action bar message format with MiniMessage and <seconds> placeholder")
    String actionBarFormat,

    @Setting("enable-title")
    @Comment("Enable title countdown during back warmup")
    boolean enableTitle,

    @Setting("title-format")
    @Comment("Title message format with MiniMessage and <seconds> placeholder")
    String titleFormat,

    @Setting("subtitle-format")
    @Comment("Subtitle message format with MiniMessage and <seconds> placeholder")
    String subtitleFormat,

    @Setting("enable-sounds")
    @Comment("Enable sound effects during back warmup and on teleport")
    boolean enableSounds,

    @Setting("tick-sound")
    @Comment("Sound played on each warmup tick countdown")
    String tickSound,

    @Setting("tick-sound-volume")
    @Comment("Volume of tick sound")
    double tickSoundVolume,

    @Setting("teleport-sound")
    @Comment("Sound played when back teleport succeeds")
    String teleportSound,

    @Setting("teleport-sound-volume")
    @Comment("Volume of teleport sound")
    double teleportSoundVolume,

    @Setting("teleport-sound-pitch")
    @Comment("Pitch of teleport sound")
    double teleportSoundPitch,

    @Setting("cancel-sound")
    @Comment("Sound played when back teleport is cancelled")
    String cancelSound,

    @Setting("cancel-sound-volume")
    @Comment("Volume of cancel sound")
    double cancelSoundVolume,

    @Setting("cancel-sound-pitch")
    @Comment("Pitch of cancel sound")
    double cancelSoundPitch,

    @Setting("max-history-depth")
    @Comment("Maximum depth of back history entries stored per player")
    int maxHistoryDepth,

    @Setting("min-teleport-distance")
    @Comment("Minimum distance (in blocks) required for a teleport to be recorded into back history")
    double minTeleportDistance,

    @Setting("track-teleports")
    @Comment("Track standard command and plugin teleports")
    boolean trackTeleports,

    @Setting("track-back-teleports")
    @Comment("Track departure location when executing /back, enabling toggle/ping-pong return")
    boolean trackBackTeleports,

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
            3,
            true,
            true,
            true,
            true,
            true,
            "YELLOW",
            "PROGRESS",
            "<prefix><gray>Teleporting back in <gold><seconds></gold>s...</gray>",
            true,
            "<gray>Teleporting back in <gold><seconds></gold> seconds...</gray>",
            false,
            "<gold><seconds></gold>",
            "<gray>Teleporting back...</gray>",
            true,
            "minecraft:block.note_block.hat",
            0.6,
            "minecraft:entity.enderman.teleport",
            0.8,
            1.0,
            "minecraft:block.note_block.bass",
            0.8,
            0.5,
            5,
            10.0,
            true,
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

        @Setting("teleport-protection-ended")
        String teleportProtectionEnded,

        @Setting("teleport-protection-start")
        String teleportProtectionStart,

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
                "<prefix><yellow>Your back teleport protection has ended.</yellow>",
                "<prefix><green>You have <gold><seconds>s</gold> of back teleport protection!</green>",
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
