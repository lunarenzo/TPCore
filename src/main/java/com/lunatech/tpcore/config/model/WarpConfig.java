package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record WarpConfig(
    @Comment("Enable or disable the Warp module completely")
    boolean enabled,

    @Comment("Warmup delay in seconds before teleporting to a warp")
    int warmupSeconds,

    @Comment("Cooldown in seconds between warp teleports")
    int cooldownSeconds,

    @Comment("Cancel teleport warmup if the player moves")
    boolean cancelOnMove,

    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Comment("Whether new warps require explicit per-warp permission node (tpcore.warp.<name>) by default")
    boolean defaultPermissionGated,

    @Comment("Default category assigned to warps if unassigned")
    String defaultCategory,

    @Comment("Number of warps displayed per page in /warps GUI menu")
    int warpsPerPage,

    @Comment("Safety checks before teleporting to a warp")
    WarpSafetyConfig safetyChecks,

    @Comment("Persistence engine configuration ('SQLITE' or 'YAML')")
    WarpStorageConfig storage,

    @Comment("Module message strings (Alphabetically ordered)")
    WarpMessages messages
) {
    public static WarpConfig createDefault() {
        return new WarpConfig(
            true,
            3,
            10,
            true,
            true,
            false,
            "general",
            8,
            WarpSafetyConfig.createDefault(),
            WarpStorageConfig.createDefault(),
            WarpMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record WarpSafetyConfig(
        boolean preventUnsafeTeleport,
        boolean preventNetherRoof,
        int maxNetherHeight
    ) {
        public static WarpSafetyConfig createDefault() {
            return new WarpSafetyConfig(true, true, 128);
        }
    }

    @ConfigSerializable
    public record WarpStorageConfig(
        String type
    ) {
        public static WarpStorageConfig createDefault() {
            return new WarpStorageConfig("SQLITE");
        }
    }

    @ConfigSerializable
    public record WarpMessages(
        String cooldownActive,
        String delWarpSuccess,
        String invalidPassword,
        String limitReached,
        String moduleDisabled,
        String noPermission,
        String onlyPlayers,
        String passwordRequired,
        String prefix,
        String setWarpConfirm,
        String setWarpSuccess,
        String teleportOtherSuccess,
        String teleportSuccess,
        String unsafeLocation,
        String warmupCancelledDamage,
        String warmupCancelledMove,
        String warmupStart,
        String warpListCategoryHeader,
        String warpListEmpty,
        String warpListHeader,
        String warpListItem,
        String warpNotFound,
        String worldNotLoaded
    ) {
        public static WarpMessages createDefault() {
            return new WarpMessages(
                "<prefix><red>You must wait <gold><seconds>s</gold> before using /warp again!</red>",
                "<prefix><green>Warp <yellow><warp></yellow> has been deleted.</green>",
                "<prefix><red>Incorrect password for warp <yellow><warp></yellow>!</red>",
                "<prefix><red>You have reached the maximum warp limit!</red>",
                "<prefix><red>Warp module is currently disabled.</red>",
                "<prefix><red>You do not have permission to access warp <yellow><warp></yellow>!</red>",
                "<prefix><red>Only players can execute this command!</red>",
                "<prefix><red>Warp <yellow><warp></yellow> is password protected! Usage: <gold>/warp <warp> <password></gold></red>",
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><yellow>Warp <gold><warp></gold> already exists! Run <gold>/setwarp <warp> -f</gold> to overwrite it.</yellow>",
                "<prefix><green>Warp <yellow><warp></yellow> set to your current location!</green>",
                "<prefix><green>Teleported <gold><target></gold> to warp <yellow><warp></yellow>!</green>",
                "<prefix><green>Teleported to warp <yellow><warp></yellow>!</green>",
                "<prefix><red>Teleport target location is unsafe or obstructed!</red>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><gray>Teleporting to warp <yellow><warp></yellow> in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>",
                "<prefix><gray>Available Warps in Category <gold><category></gold> (Page <gold><page></gold>/<gold><maxpages></gold>):</gray>",
                "<prefix><yellow>No warps found.</yellow>",
                "<prefix><gray>Available Warps (Page <gold><page></gold>/<gold><maxpages></gold>):</gray>",
                "  <dark_gray>•</dark_gray> <green><name></green> <gray>[<yellow><category></yellow>]</gray> <click:run_command:'/warp <name>'><hover:show_text:'<green>Click to teleport</green><br><gray>World: <yellow><world></yellow><br>Coords: <yellow><x>, <y>, <z></yellow>'><gold>[Teleport]</gold></hover></click>",
                "<prefix><red>Warp <yellow><warp></yellow> was not found!</red>",
                "<prefix><red>Target world <yellow><world></yellow> is not currently loaded!</red>"
            );
        }
    }
}
