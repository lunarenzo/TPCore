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

    @Comment("Number of entries displayed per page in /warps chat browsing")
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
    WarpMessages messages,
    int maxConcurrentOperations,
    int maxCachedWarps,
    int maxPlayerStateEntries,
    int storageQueueCapacity,
    int operationTimeoutSeconds,
    long maxStorageBytes,
    WarpEditMessages editMessages,
    WarpControlConfig control,
    WarpControlMessages controlMessages,
    WarpBrowseConfig browsing
) {
    public WarpConfig {
        if (editMessages == null) editMessages = WarpEditMessages.createDefault();
        if (control == null) control = WarpControlConfig.createDefault();
        if (browsing == null) browsing = WarpBrowseConfig.createDefault();
        if (controlMessages == null) controlMessages = WarpControlMessages.createDefault();
        maxConcurrentOperations = Math.max(1, Math.min(1024, maxConcurrentOperations));
        maxCachedWarps = Math.max(1, Math.min(16384, maxCachedWarps));
        maxPlayerStateEntries = Math.max(1, Math.min(16384, maxPlayerStateEntries));
        storageQueueCapacity = Math.max(1, Math.min(1024, storageQueueCapacity));
        operationTimeoutSeconds = Math.max(1, Math.min(300, operationTimeoutSeconds));
        maxStorageBytes = Math.max(1024, Math.min(134217728L, maxStorageBytes));
        if (!Double.isFinite(warpCost) || warpCost < 0 || !Double.isFinite(setWarpCost) || setWarpCost < 0) {
            throw new IllegalArgumentException("Warp costs must be finite and nonnegative");
        }
    }

    public WarpConfig(boolean enabled, int warmupSeconds, int cooldownSeconds, boolean cancelOnMove,
        boolean cancelOnDamage, boolean defaultPermissionGated, String defaultCategory, int warpsPerPage,
        int maxWarps, boolean economyEnabled, double warpCost, double setWarpCost, String chargeTiming,
        boolean refundOnCancel, WarpSafetyConfig safetyChecks, WarpStorageConfig storage, WarpMessages messages) {
        this(enabled, warmupSeconds, cooldownSeconds, cancelOnMove, cancelOnDamage, defaultPermissionGated,
            defaultCategory, warpsPerPage, maxWarps, economyEnabled, warpCost, setWarpCost, chargeTiming,
            refundOnCancel, safetyChecks, storage, messages, 64, 4096, 2048, 128, 30, 16777216L, WarpEditMessages.createDefault(), WarpControlConfig.createDefault(), WarpControlMessages.createDefault());
    }
    public WarpConfig(boolean enabled, int warmupSeconds, int cooldownSeconds, boolean cancelOnMove,
        boolean cancelOnDamage, boolean defaultPermissionGated, String defaultCategory, int warpsPerPage,
        int maxWarps, boolean economyEnabled, double warpCost, double setWarpCost, String chargeTiming,
        boolean refundOnCancel, WarpSafetyConfig safetyChecks, WarpStorageConfig storage, WarpMessages messages,
        int maxConcurrentOperations, int maxCachedWarps, int maxPlayerStateEntries, int storageQueueCapacity,
        int operationTimeoutSeconds, long maxStorageBytes) {
        this(enabled, warmupSeconds, cooldownSeconds, cancelOnMove, cancelOnDamage, defaultPermissionGated,
            defaultCategory, warpsPerPage, maxWarps, economyEnabled, warpCost, setWarpCost, chargeTiming,
            refundOnCancel, safetyChecks, storage, messages, maxConcurrentOperations, maxCachedWarps,
            maxPlayerStateEntries, storageQueueCapacity, operationTimeoutSeconds, maxStorageBytes, WarpEditMessages.createDefault(), WarpControlConfig.createDefault(), WarpControlMessages.createDefault());
    }

    public WarpConfig(boolean enabled, int warmupSeconds, int cooldownSeconds, boolean cancelOnMove,
        boolean cancelOnDamage, boolean defaultPermissionGated, String defaultCategory, int warpsPerPage,
        int maxWarps, boolean economyEnabled, double warpCost, double setWarpCost, String chargeTiming,
        boolean refundOnCancel, WarpSafetyConfig safetyChecks, WarpStorageConfig storage, WarpMessages messages,
        int maxConcurrentOperations, int maxCachedWarps, int maxPlayerStateEntries, int storageQueueCapacity,
        int operationTimeoutSeconds, long maxStorageBytes, WarpEditMessages editMessages) {
        this(enabled, warmupSeconds, cooldownSeconds, cancelOnMove, cancelOnDamage, defaultPermissionGated,
            defaultCategory, warpsPerPage, maxWarps, economyEnabled, warpCost, setWarpCost, chargeTiming,
            refundOnCancel, safetyChecks, storage, messages, maxConcurrentOperations, maxCachedWarps,
            maxPlayerStateEntries, storageQueueCapacity, operationTimeoutSeconds, maxStorageBytes, editMessages,
            WarpControlConfig.createDefault(), WarpControlMessages.createDefault());
    }

    public WarpConfig(boolean enabled, int warmupSeconds, int cooldownSeconds, boolean cancelOnMove,
        boolean cancelOnDamage, boolean defaultPermissionGated, String defaultCategory, int warpsPerPage,
        int maxWarps, boolean economyEnabled, double warpCost, double setWarpCost, String chargeTiming,
        boolean refundOnCancel, WarpSafetyConfig safetyChecks, WarpStorageConfig storage, WarpMessages messages,
        int maxConcurrentOperations, int maxCachedWarps, int maxPlayerStateEntries, int storageQueueCapacity,
        int operationTimeoutSeconds, long maxStorageBytes, WarpEditMessages editMessages, WarpControlConfig control,
        WarpControlMessages controlMessages) {
        this(enabled, warmupSeconds, cooldownSeconds, cancelOnMove, cancelOnDamage, defaultPermissionGated,
            defaultCategory, warpsPerPage, maxWarps, economyEnabled, warpCost, setWarpCost, chargeTiming,
            refundOnCancel, safetyChecks, storage, messages, maxConcurrentOperations, maxCachedWarps,
            maxPlayerStateEntries, storageQueueCapacity, operationTimeoutSeconds, maxStorageBytes, editMessages,
            control, controlMessages, WarpBrowseConfig.createDefault());
    }

    @ConfigSerializable
    public record WarpControlConfig(int deleteConfirmationSeconds, int recoveryMaxEntries, int recoveryRetentionDays, int closureReasonMaxLength) {
        public WarpControlConfig {
            deleteConfirmationSeconds = Math.max(1, Math.min(300, deleteConfirmationSeconds));
            recoveryMaxEntries = Math.max(1, Math.min(4096, recoveryMaxEntries));
            recoveryRetentionDays = Math.max(1, Math.min(365, recoveryRetentionDays));
            closureReasonMaxLength = Math.max(1, Math.min(4096, closureReasonMaxLength));
        }
        public static WarpControlConfig createDefault() { return new WarpControlConfig(30, 100, 7, 256); }
    }

    @ConfigSerializable
    public record WarpControlMessages(String closed, String changed, String deleteConfirmation, String confirmationExpired,
        String recoveryHeader, String recoveryEntry, String recoveryEmpty, String recoveryNotFound,
        String recoverySuccess, String recoveryConflict, String infoState, String defaultClosureReason,
        String closedLabel, String openLabel, String usage) {
        public static WarpControlMessages createDefault() {
            return new WarpControlMessages(
                "<prefix><red>Warp <yellow><warp></yellow> is closed: <reason></red>",
                "<prefix><yellow>Warp <warp> changed before teleportation. Please try again.</yellow>",
                "<prefix><yellow>Run /delwarp <warp> confirm within <seconds>s to archive and delete this warp.</yellow>",
                "<prefix><red>Deletion confirmation expired or the warp changed. Run /delwarp <warp> again.</red>",
                "<prefix><gold>Deleted warps (page <page>/<maxpages>):</gold>",
                "<gray><warp> | deleted at <deleted-at> | /warprecover <id></gray>",
                "<prefix><yellow>No recoverable deleted warps.</yellow>",
                "<prefix><red>Recovery entry was not found or has expired.</red>",
                "<prefix><green>Restored warp <warp> with its original metadata.</green>",
                "<prefix><red>Cannot restore: a warp with that name already exists.</red>",
                "<prefix><gray>Closed: <closed>; unlisted: <unlisted>; closure reason: <reason></gray>",
                "Temporarily unavailable.", "[Closed]", "",
                "<prefix><yellow>/editwarp <name> close [reason] | open | unlisted | listed; /delwarp <name> [confirm]; /warprecover list [page] | <entry-id></yellow>"
            );
        }
    }

    @ConfigSerializable
    public record WarpEditMessages(String usage, String success, String renameSuccess, String renameCollision, String invalidValue, String info) {
        public static WarpEditMessages createDefault() {
            return new WarpEditMessages(
                "<prefix><yellow>Usage: /warpinfo <name> or /editwarp <name> rename <new-name> | relocate | category <category> | password set <password> | password clear | permission required | permission public</yellow>",
                "<prefix><green>Updated warp <yellow><warp></yellow>.</green>",
                "<prefix><green>Renamed <yellow><old-name></yellow> to <yellow><warp></yellow>.</green> <yellow>Per-warp permission: <old-permission> → <permission>. Update external permission grants if required.</yellow>",
                "<prefix><red>Cannot rename: warp <yellow><warp></yellow> already exists.</red>",
                "<prefix><red>Invalid edit value. Categories use 1-32 letters, digits, underscores or hyphens; passwords must not be blank.</red>",
                "<prefix><gold>Warp <warp></gold><newline><gray>World: <world> (<world-id>)<newline>Position: <x>, <y>, <z>; yaw <yaw>, pitch <pitch><newline>Category: <category><newline>Password protected: <password-protected><newline>Permission required: <permission-required> (<permission>)<newline>Creator UUID: <creator><newline>Created at (epoch ms): <created-at></gray>"
            );
        }
    }

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
        String worldNotLoaded,
        String operationFailed,
        String operationBusy,
        String storageNotReady,
        String economyUnavailable
    ) {
        public WarpMessages(String cooldownActive, String costDeducted, String costRefunded, String delWarpSuccess, String delWarpUsage, String insufficientFunds, String invalidPassword, String invalidWarpName, String limitReached, String moduleDisabled, String noPermission, String onlyPlayers, String passwordRequired, String playerNotOnline, String prefix, String setWarpConfirm, String setWarpSuccess, String setWarpUsage, String teleportOtherSuccess, String teleportSuccess, String teleportedByOther, String unsafeLocation, String warmupAlreadyActive, String warmupCancelledDamage, String warmupCancelledMove, String warmupStart, String warpListCategoryHeader, String warpListEmpty, String warpListHeader, String warpListItem, String warpNotFound, String warpOtherUsage, String warpUsage, String worldNotLoaded) {
            this(cooldownActive, costDeducted, costRefunded, delWarpSuccess, delWarpUsage, insufficientFunds, invalidPassword, invalidWarpName, limitReached, moduleDisabled, noPermission, onlyPlayers, passwordRequired, playerNotOnline, prefix, setWarpConfirm, setWarpSuccess, setWarpUsage, teleportOtherSuccess, teleportSuccess, teleportedByOther, unsafeLocation, warmupAlreadyActive, warmupCancelledDamage, warmupCancelledMove, warmupStart, warpListCategoryHeader, warpListEmpty, warpListHeader, warpListItem, warpNotFound, warpOtherUsage, warpUsage, worldNotLoaded, createDefault().operationFailed(), createDefault().operationBusy(), createDefault().storageNotReady(), createDefault().economyUnavailable());
        }

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
                "  <dark_gray>•</dark_gray> <green><name></green> <red><availability></red> <gray>[<yellow><category></yellow>]</gray> <click:run_command:'/warp <name>'><hover:show_text:'<green>Click to teleport</green><br><gray>World: <yellow><world></yellow><br>Coords: <yellow><x>, <y>, <z></yellow>'><gold>[Teleport]</gold></hover></click>",
                "<prefix><red>Warp <yellow><warp></yellow> was not found!</red>",
                "<prefix><red>Usage: <gold>/warpother <target> <name></gold></red>",
                "<prefix><red>Usage: <gold>/warp <name> [password]</gold></red>",
                "<prefix><red>Target world <yellow><world></yellow> is not currently loaded!</red>",
                "<prefix><red>Warp operation failed. Please contact an administrator.</red>",
                "<prefix><yellow>Warp service is busy. Please try again shortly.</yellow>",
                "<prefix><yellow>Warp storage is not ready. Please try again shortly.</yellow>",
                "<prefix><red>Paid warps require an available native asynchronous economy provider.</red>"
            );
        }
    }
}
