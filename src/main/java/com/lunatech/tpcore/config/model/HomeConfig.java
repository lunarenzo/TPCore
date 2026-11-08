package com.lunatech.tpcore.config.model;

import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record HomeConfig(
    @Comment("Enable or disable the Home module completely")
    boolean enabled,

    @Comment("Benchmark submodule feature configuration")
    HomeBenchmarkConfig benchmark,

    @Comment("Default home name when /sethome or /home is executed without arguments")
    String defaultHomeName,

    @Comment("Warmup delay in seconds before teleporting to a home")
    int warmupSeconds,

    @Comment("Cooldown in seconds between home teleports")
    int cooldownSeconds,

    @Comment("Cancel teleport warmup if the player moves")
    boolean cancelOnMove,

    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Comment("Reserved compatibility setting; sharing commands are not exposed")
    boolean enableHomeSharing,

    @Comment("Automatically respawn players at their primary home if no bed/anchor is set")
    boolean respawnAtPrimaryHome,

    @Comment("Require confirmation or force flag before overwriting an existing home")
    boolean requireOverwriteConfirmation,

    @Comment("Safety checks before teleporting to a home")
    HomeSafetyConfig safetyChecks,

    @Comment("World restrictions for saving homes and travelling to their destinations")
    HomeWorldRestrictions worldRestrictions,

    @Comment("Dynamic home limits mapped to permissions (tpcore.homes.limit.<key>)")
    Map<String, Integer> homeLimits,

    @Comment("Module message strings (Alphabetically ordered)")
    HomeMessages messages,
    HomeRuntimeConfig runtime,
    HomeGuiConfig gui
) {
    public HomeConfig {
        if (runtime == null) runtime = HomeRuntimeConfig.createDefault();
        if (gui == null) gui = HomeGuiConfig.createDefault();
        if (benchmark == null) benchmark = HomeBenchmarkConfig.createDefault();
        if (safetyChecks == null) safetyChecks = HomeSafetyConfig.createDefault();
        if (homeLimits == null) homeLimits = Map.of("default", 3);
        else homeLimits = Map.copyOf(homeLimits);
        if (messages == null) messages = HomeMessages.createDefault();
        if (defaultHomeName == null) defaultHomeName = "home";
        warmupSeconds = Math.clamp(warmupSeconds, 0, 300);
        cooldownSeconds = Math.clamp(cooldownSeconds, 0, 86400);
    }

    public static HomeConfig createDefault() {
        return new HomeConfig(
            true,
            HomeBenchmarkConfig.createDefault(),
            "home",
            3,
            10,
            true,
            true,
            true,
            false,
            true,
            HomeSafetyConfig.createDefault(),
            HomeWorldRestrictions.createDefault(),
            Map.of(
                "default", 3,
                "vip", 5,
                "mvp", 10,
                "staff", 50
            ),
            HomeMessages.createDefault(),
            HomeRuntimeConfig.createDefault(),
            HomeGuiConfig.createDefault()
        );
    }

    @ConfigSerializable
    public record HomeBenchmarkConfig(
        @Comment("Enable or disable the benchmark submodule feature completely (Disabled by default)")
        boolean enabled
    ) {
        public static HomeBenchmarkConfig createDefault() {
            return new HomeBenchmarkConfig(false);
        }
    }

    @ConfigSerializable
    public record HomeSafetyConfig(
        boolean preventUnsafeTeleport,
        boolean preventNetherRoof,
        int maxNetherHeight,
        int maxConcurrentChunkLoads
    ) {
        public static HomeSafetyConfig createDefault() {
            return new HomeSafetyConfig(true, true, 128, 8);
        }
    }

    @ConfigSerializable
    public record HomeWorldRestrictions(
        String mode,
        List<String> worlds
    ) {
        public static HomeWorldRestrictions createDefault() {
            return new HomeWorldRestrictions("BLACKLIST", List.of("world_nether_resource", "minigames"));
        }
    }

    @ConfigSerializable
    public record HomeMessages(
        String alreadyShared,
        String benchmarkDisabled,
        String benchmarkHeader,
        String benchmarkReportSaved,
        String benchmarkResults,
        String cooldownActive,
        String delHomeSuccess,
        String delHomeSuccessOther,
        String homeListEmpty,
        String homeListHeader,
        String homeListItem,
        String homeNotFound,
        String homeNotFoundOther,
        String homeOverwritten,
        String limitReached,
        String moduleDisabled,
        String noHomeSet,
        String notShared,
        String onlyPlayers,
        String prefix,
        String respawnAtHome,
        String setHomeConfirm,
        String setHomeSuccess,
        String setHomeSuccessOther,
        String shareSuccess,
        String sharedHomeNotFound,
        String teleportSuccess,
        String unshareSuccess,
        String unsafeLocation,
        String warmupCancelledDamage,
        String warmupCancelledMove,
        String warmupStart,
        String worldRestricted
    ) {
        public static HomeMessages createDefault() {
            return new HomeMessages(
                "<prefix><red>Home <yellow><home></yellow> is already shared with <green><target></green>!</red>",
                "<prefix><red>Benchmark submodule feature is currently disabled in home.yml.</red>",
                "<prefix><gradient:#00D2FF:#3A7BD5><bold>CTCPE Engine Full Benchmark Running...</bold></gradient> <gray>(Simulating <gold><count></gold> sethome/home/delhome operations across <yellow><worlds></yellow>...)</gray>",
                "<prefix><gray>Diagnostic report file saved to: <gold><path></gold></gray>",
                "<prefix><gray>Storage benchmark complete.</gray>",
                "<prefix><red>You must wait <gold><seconds>s</gold> before using /home again!</red>",
                "<prefix><green>Home <yellow><home></yellow> has been deleted.</green>",
                "<prefix><green>Deleted home <yellow><home></yellow> for player <gold><target></gold>.</green>",
                "<prefix><yellow>You have no saved homes.</yellow>",
                "<prefix><gray>Your Homes (<gold><used></gold>/<gold><max></gold>):</gray>",
                "  <dark_gray>•</dark_gray> <green><name></green> <gray>(<hover:show_text:'<gray>World: <yellow><world></yellow><br>Coords: <yellow><x>, <y>, <z></yellow>'><yellow><world></yellow></hover>)</gray> <click:run_command:'/home <name>'><hover:show_text:'<green>Click to teleport</green>'><gold>[Teleport]</gold></hover></click>",
                "<prefix><red>Home <yellow><home></yellow> was not found!</red>",
                "<prefix><red>Home <yellow><home></yellow> was not found for player <gold><target></gold>!</red>",
                "<prefix><yellow>Home <gold><home></gold> has been updated with your current location.</yellow>",
                "<prefix><red>You have reached your limit of <gold><max></gold> homes!</red>",
                "<prefix><red>Home module is currently disabled.</red>",
                "<prefix><red>You do not have a default home set! Use /sethome first.</red>",
                "<prefix><red>Home <yellow><home></yellow> is not shared with <green><target></green>!</red>",
                "<prefix><red>Only players can execute this command!</red>",
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><yellow>Teleported to your primary home after respawn!</yellow>",
                "<prefix><yellow>Home <gold><home></gold> already exists! Run <gold>/sethome <home> -f</gold> to overwrite it.</yellow>",
                "<prefix><green>Home <yellow><home></yellow> set to your current location!</green>",
                "<prefix><green>Set home <yellow><home></yellow> for player <gold><target></gold>.</green>",
                "<prefix><green>Shared home <yellow><home></yellow> with <gold><target></gold>!</green>",
                "<prefix><red>Shared home <yellow><home></yellow> from owner <gold><owner></gold> was not found!</red>",
                "<prefix><green>Teleported to home <yellow><home></yellow>!</green>",
                "<prefix><green>Revoked access to home <yellow><home></yellow> for <gold><target></gold>.</green>",
                "<prefix><red>Teleport target location is unsafe or obstructed!</red>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><gray>Teleporting to home <yellow><home></yellow> in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>",
                "<prefix><red>You cannot set homes in this world!</red>"
            );
        }
    }
}

