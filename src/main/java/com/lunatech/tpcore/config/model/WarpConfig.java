package com.lunatech.tpcore.config.model;

import java.util.Locale;
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

    @Comment("Maximum total server warps allowed (0 for unlimited)")
    int maxWarps,

    @Comment("Enable Vault economy integration for warp teleport and creation costs")
    boolean economyEnabled,

    @Comment("Cost charged to player when teleporting to a warp")
    double warpCost,

    @Comment("Cost charged to player when creating a warp via /setwarp")
    double setWarpCost,

    @Comment("Charge timing mode: CHARGE_ON_WARMUP or CHARGE_ON_SUCCESS")
    String chargeTiming,

    @Comment("Refund teleportation cost if warmup is cancelled (by movement, damage, or teleport)")
    boolean refundOnCancel,

    @Comment("Safety checks before teleporting to a warp")
    WarpSafetyConfig safetyChecks,

    @Comment("Persistence engine configuration ('SQLITE' or 'YAML')")
    WarpStorageConfig storage,

    @Comment("Module message strings (Alphabetically ordered)")
    WarpMessages messages
) {
    public String getNormalizedChargeTiming() {
        if (this.chargeTiming == null || this.chargeTiming.isBlank()) {
            return "CHARGE_ON_WARMUP";
        }
        String upper = this.chargeTiming.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "CHARGE_ON_SUCCESS", "SUCCESS", "ON_SUCCESS" -> "CHARGE_ON_SUCCESS";
            case "CHARGE_ON_START", "START", "ON_START", "CHARGE_ON_WARMUP", "WARMUP", "ON_WARMUP" -> "CHARGE_ON_WARMUP";
            default -> "CHARGE_ON_WARMUP";
        };
    }

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
            100,
            false,
            0.0,
            0.0,
            "CHARGE_ON_WARMUP",
            true,
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
        String costDeducted,
        String costRefunded,
        String delWarpSuccess,
        String delWarpUsage,
        String insufficientFunds,
        String invalidPassword,
        String invalidWarpName,
        String limitReached,
        String moduleDisabled,
        String noPermission,
        String onlyPlayers,
        String passwordRequired,
        String playerNotOnline,
        String prefix,
        String setWarpConfirm,
        String setWarpSuccess,
        String setWarpUsage,
        String teleportOtherSuccess,
        String teleportSuccess,
        String teleportedByOther,
        String unsafeLocation,
        String warmupAlreadyActive,
        String warmupCancelledDamage,
        String warmupCancelledMove,
        String warmupStart,
        String warpListCategoryHeader,
        String warpListEmpty,
        String warpListHeader,
        String warpListItem,
        String warpNotFound,
        String warpOtherUsage,
        String warpUsage,
        String worldNotLoaded
    ) {
        public static WarpMessages createDefault() {
            return new WarpMessages(
                "<prefix><red>You must wait <gold><seconds>s</gold> before using /warp again!</red>",
                "<prefix><gold><cost></gold> <green>has been deducted from your account.</green>",
                "<prefix><gold><cost></gold> <green>has been refunded to your account.</green>",
                "<prefix><green>Warp <yellow><warp></yellow> has been deleted.</green>",
                "<prefix><red>Usage: <gold>/delwarp <name></gold></red>",
                "<prefix><red>You do not have enough money! Required: <gold><cost></gold>, Balance: <gold><balance></gold></red>",
                "<prefix><red>Incorrect password for warp <yellow><warp></yellow>!</red>",
                "<prefix><red>Invalid warp name! Warp names must be 1-32 alphanumeric characters.</red>",
                "<prefix><red>You have reached the maximum warp limit!</red>",
                "<prefix><red>Warp module is currently disabled.</red>",
                "<prefix><red>You do not have permission to access warp <yellow><warp></yellow>!</red>",
                "<prefix><red>Only players can execute this command!</red>",
                "<prefix><red>Warp <yellow><warp></yellow> is password protected! Usage: <gold>/warp <warp> <password></gold></red>",
                "<prefix><red>Player <yellow><target></yellow> is not currently online!</red>",
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><yellow>Warp <gold><warp></gold> already exists! Run <gold>/setwarp <warp> -f</gold> to overwrite it.</yellow>",
                "<prefix><green>Warp <yellow><warp></yellow> set to your current location!</green>",
                "<prefix><red>Usage: <gold>/setwarp <name> [-f] [category] [password]</gold></red>",
                "<prefix><green>Teleported <gold><target></gold> to warp <yellow><warp></yellow>!</green>",
                "<prefix><green>Teleported to warp <yellow><warp></yellow>!</green>",
                "<prefix><green>You were teleported to warp <yellow><warp></yellow> by <gold><sender></gold>!</green>",
                "<prefix><red>Teleport target location is unsafe or obstructed!</red>",
                "<prefix><red>You already have a warp teleport in progress!</red>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><gray>Teleporting to warp <yellow><warp></yellow> in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>",
                "<prefix><gray>Available Warps in Category <gold><category></gold> (Page <gold><page></gold>/<gold><maxpages></gold>):</gray>",
                "<prefix><yellow>No warps found.</yellow>",
                "<prefix><gray>Available Warps (Page <gold><page></gold>/<gold><maxpages></gold>):</gray>",
                "  <dark_gray>•</dark_gray> <green><name></green> <gray>[<yellow><category></yellow>]</gray> <click:run_command:'/warp <name>'><hover:show_text:'<green>Click to teleport</green><br><gray>World: <yellow><world></yellow><br>Coords: <yellow><x>, <y>, <z></yellow>'><gold>[Teleport]</gold></hover></click>",
                "<prefix><red>Warp <yellow><warp></yellow> was not found!</red>",
                "<prefix><red>Usage: <gold>/warpother <target> <name></gold></red>",
                "<prefix><red>Usage: <gold>/warp <name> [password]</gold></red>",
                "<prefix><red>Target world <yellow><world></yellow> is not currently loaded!</red>"
            );
        }
    }
}
