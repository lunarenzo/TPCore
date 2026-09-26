package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record TpaConfig(
    @Comment("Enable or disable the TPA module completely")
    boolean enabled,

    @Comment("Timeout in seconds before an unaccepted TPA request automatically expires")
    int requestTimeoutSeconds,

    @Comment("Cooldown in seconds between outgoing TPA requests")
    int requestCooldownSeconds,

    @Comment("Maximum number of pending incoming TPA requests allowed per target player (DOS/spam guard)")
    int maxPendingRequestsPerPlayer,

    @Comment("Warmup delay in seconds before teleporting")
    int warmupSeconds,

    @Comment("Cancel teleport warmup if the player moves > 0.5 blocks")
    boolean cancelOnMove,

    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Comment("Allow players to send TPA requests to themselves")
    boolean allowSelfTpa,

    @Comment("Require destination ground safety before teleportation")
    boolean requireSafeLocation,

    @Comment("Teleport protection duration in seconds after teleportation (0 to disable)")
    int protectionSeconds,

    @Comment("Cancel teleport protection if the protected player attacks another entity")
    boolean protectionCancelOnAttack,

    @Comment("Protect against all damage types (true) or PVP damage only (false)")
    boolean protectionAllDamage,

    @Comment("Whether to enable confirmation menus (GUI/Dialog) for TPA requests")
    boolean enableConfirmationMenu,

    @Comment("Enable confirmation menu specifically for /tpa command")
    boolean enableTpaConfirm,

    @Comment("Enable confirmation menu specifically for /tpahere command")
    boolean enableTpahereConfirm,

    @Comment("Enable confirmation menu specifically for /tpaccept command")
    boolean enableTpacceptConfirm,

    @Comment("Confirmation menu mode: GUI or DIALOG (Default: GUI)")
    String confirmationMode,

    @Comment("Title for the 3-row Chest Confirmation GUI")
    String guiTitle,

    @Comment("Slot for the Accept button (0-26, default 15)")
    int guiAcceptSlot,

    @Comment("Slot for the Deny/Cancel button (0-26, default 11)")
    int guiDenySlot,

    @Comment("Slot for the Player Head (0-26, default 13)")
    int guiHeadSlot,

    @Comment("Item material for Accept button")
    String guiAcceptItem,

    @Comment("Display name for Accept button")
    String guiAcceptName,

    @Comment("Item material for Deny button")
    String guiDenyItem,

    @Comment("Display name for Deny button")
    String guiDenyName,

    @Comment("Item material for Background filler")
    String guiFillItem,

    @Comment("Whether to fill empty GUI slots with filler material")
    boolean guiFillEmptySlots,

    @Comment("Title for Paper Dialog confirmation menu (1.21.6+)")
    String dialogTitle,

    @Comment("Legacy Body text for Paper Dialog menu (fallback)")
    String dialogBodyText,

    @Comment("Body text when sender confirms /tpa")
    String dialogSendTpaBodyText,

    @Comment("Body text when sender confirms /tpahere")
    String dialogSendTpahereBodyText,

    @Comment("Body text when target accepts /tpa request")
    String dialogAcceptTpaBodyText,

    @Comment("Body text when target accepts /tpahere request")
    String dialogAcceptTpahereBodyText,

    @Comment("Confirm button label for sender Paper Dialog menu")
    String dialogSendConfirmText,

    @Comment("Cancel button label for sender Paper Dialog menu")
    String dialogSendCancelText,

    @Comment("Accept button label for Paper Dialog menu")
    String dialogAcceptText,

    @Comment("Deny button label for Paper Dialog menu")
    String dialogDenyText,

    @Comment("Enable or disable Action Bar countdown feedback")
    boolean enableActionBar,

    @Comment("MiniMessage format for Action Bar countdown")
    String actionBarFormat,

    @Comment("Enable or disable Title & Subtitle countdown feedback")
    boolean enableTitle,

    @Comment("MiniMessage format for main Title text")
    String titleFormat,

    @Comment("MiniMessage format for Subtitle text")
    String subtitleFormat,

    @Comment("MiniMessage format for Title when teleport is cancelled")
    String cancelTitleFormat,

    @Comment("Enable or disable BossBar countdown feedback")
    boolean enableBossbar,

    @Comment("MiniMessage format for BossBar title")
    String bossbarFormat,

    @Comment("BossBar color (BLUE, GREEN, PINK, PURPLE, RED, WHITE, YELLOW)")
    String bossbarColor,

    @Comment("BossBar overlay/style (PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20)")
    String bossbarOverlay,

    @Comment("Enable or disable countdown audio feedback")
    boolean enableSounds,

    @Comment("Sound played on each countdown tick (e.g. block.note_block.pling)")
    String tickSound,

    @Comment("Volume for tick sound")
    double tickSoundVolume,

    @Comment("Pitch increase per tick step")
    double tickSoundPitchStep,

    @Comment("Sound played when warmup completes and teleportation occurs")
    String completionSound,

    @Comment("Volume for completion sound")
    double completionSoundVolume,

    @Comment("Pitch for completion sound")
    double completionSoundPitch,

    @Comment("Sound played when warmup is cancelled")
    String cancelSound,

    @Comment("Volume for cancel sound")
    double cancelSoundVolume,

    @Comment("Pitch for cancel sound")
    double cancelSoundPitch,

    @Comment("Module message strings (Alphabetically ordered)")
    TpaMessages messages
) {
    public static TpaConfig createDefault() {
        return new TpaConfig(
            true, 30, 10, 5, 3, true, true, false, true, 4, true, false,
            true,
            true,
            true,
            true,
            "GUI",
            "<gradient:#00D2FF:#3A7BD5><bold>Teleport Request</bold></gradient>",
            15, 11, 13,
            "LIME_STAINED_GLASS_PANE",
            "<green><bold>ACCEPT REQUEST</bold></green>",
            "RED_STAINED_GLASS_PANE",
            "<red><bold>DENY REQUEST</bold></red>",
            "GRAY_STAINED_GLASS_PANE",
            true,
            "<gradient:#00D2FF:#3A7BD5><bold>Teleport Confirmation</bold></gradient>",
            "<yellow><sender></yellow> sent a teleport request.\nDo you accept?",
            "<gray>Send a teleport request to </gray><yellow><target></yellow>?\n<gray>You will be teleported to their location once accepted.</gray>",
            "<gray>Request </gray><yellow><target></yellow><gray> to teleport to you?</gray>\n<gray>They will be teleported to your location once accepted.</gray>",
            "<yellow><sender></yellow><gray> wants to teleport to your location.</gray>\n<gray>Do you accept?</gray>",
            "<yellow><sender></yellow><gray> requests you to teleport to their location.</gray>\n<gray>Do you accept?</gray>",
            "<green><bold>CONFIRM & SEND</bold></green>",
            "<red><bold>CANCEL</bold></red>",
            "<green><bold>ACCEPT</bold></green>",
            "<red><bold>DENY</bold></red>",
            true,
            "<gold>Teleporting in <yellow><seconds>s</yellow>... Do not move!</gold>",
            true,
            "<gold><bold>TELEPORTING</bold></gold>",
            "<gray>in <yellow><seconds>s</yellow>... Stay still!</gray>",
            "<red><bold>TPA CANCELLED</bold></red>",
            true,
            "<gold>Teleporting in <yellow><seconds>s</yellow>...</gold>",
            "YELLOW",
            "PROGRESS",
            true,
            "block.note_block.pling",
            0.8,
            0.15,
            "entity.player.teleport",
            1.0,
            1.0,
            "entity.villager.no",
            0.8,
            1.0,
            TpaMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record TpaMessages(
        String alreadyHasPendingRequest,
        String autoAcceptOff,
        String autoAcceptOn,
        String blockListEmpty,
        String blockListHeader,
        String cooldownActive,
        String maxPendingRequestsReached,
        String multiplePendingRequests,
        String noBulkPermission,
        String noPendingRequests,
        String notBlocked,
        String playerBlocked,
        String playerNotOnline,
        String playerUnblocked,
        String prefix,
        String rejectSelfTpa,
        String requestAcceptedSender,
        String requestAcceptedTarget,
        String requestAutoAcceptedSender,
        String requestAutoAcceptedTarget,
        String requestCancelledSender,
        String requestCancelledTarget,
        String requestDeniedSender,
        String requestDeniedTarget,
        String requestExpired,
        String senderTpaHereSent,
        String senderTpaSent,
        String targetTpaHereReceived,
        String targetTpaReceived,
        String targetToggledOff,
        String teleportProtectionEnded,
        String teleportProtectionStart,
        String toggleOff,
        String toggleOn,
        String unsafeDestination,
        String warmupCancelledDamage,
        String warmupCancelledMove,
        String warmupStart
    ) {
        public static TpaMessages createDefault() {
            return new TpaMessages(
                "<prefix><red>You already have an active pending teleport request with <yellow><target></yellow>!</red>",
                "<prefix><gray>TPA Auto-Accept is now <red>DISABLED</red>.</gray>",
                "<prefix><gray>TPA Auto-Accept is now <green>ENABLED</green>.</gray>",
                "<prefix><gray>You have no blocked players.</gray>",
                "<prefix><gray>Blocked Players: <yellow><players></yellow></gray>",
                "<prefix><red>Please wait <gold><seconds>s</gold> before sending another TPA request!</red>",
                "<prefix><red>Player <yellow><target></yellow> has reached the maximum allowed pending teleport requests!</red>",
                "<prefix><red>You have multiple requests! Specify the player name: <yellow>/tpaccept <player></yellow></red>",
                "<prefix><red>You do not have permission to send bulk TPA requests to multiple players!</red>",
                "<prefix><red>You have no active pending teleport requests!</red>",
                "<prefix><red>Player <yellow><player></yellow> is not in your block list!</red>",
                "<prefix><gray>Blocked <yellow><player></yellow> from sending you teleport requests.</gray>",
                "<prefix><red>Player <yellow><player></yellow> is not online!</red>",
                "<prefix><green>Unblocked <yellow><player></yellow>.</green>",
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><red>You cannot send a teleport request to yourself!</red>",
                "<prefix><yellow><target></yellow> <green>accepted your teleport request!</green>",
                "<prefix><green>Accepted teleport request from <yellow><sender></yellow>.</green>",
                "<prefix><yellow><target></yellow> <green>auto-accepted your teleport request!</green>",
                "<prefix><gray>Auto-accepted teleport request from <yellow><sender></yellow>.</gray>",
                "<prefix><gray>Cancelled your teleport request to <yellow><target></yellow>.</gray>",
                "<prefix><yellow><sender></yellow> <gray>cancelled their teleport request.</gray>",
                "<prefix><yellow><target></yellow> <red>denied your teleport request.</red>",
                "<prefix><red>Denied teleport request from <yellow><sender></yellow>.</red>",
                "<prefix><red>The teleport request with <yellow><player></yellow> has expired.</red>",
                "<prefix><gray>Sent a request for <yellow><target></yellow> to teleport to you. Expires in <gold><seconds>s</gold>.</gray>",
                "<prefix><gray>Sent a teleport request to <yellow><target></yellow>. Expires in <gold><seconds>s</gold>.</gray>",
                "<prefix><yellow><sender></yellow> <gray>requests you to teleport to them.</gray>\n<prefix><green><click:run_command:'/tpaccept <sender>'><hover:show_text:'<green>Click to ACCEPT teleport request</green>'><bold>[ACCEPT]</bold></click></green>  <red><click:run_command:'/tpdeny <sender>'><hover:show_text:'<red>Click to DENY teleport request</red>'><bold>[DENY]</bold></click></red>",
                "<prefix><yellow><sender></yellow> <gray>wants to teleport to you.</gray>\n<prefix><green><click:run_command:'/tpaccept <sender>'><hover:show_text:'<green>Click to ACCEPT teleport request</green>'><bold>[ACCEPT]</bold></click></green>  <red><click:run_command:'/tpdeny <sender>'><hover:show_text:'<red>Click to DENY teleport request</red>'><bold>[DENY]</bold></click></red>",
                "<prefix><red>Player <yellow><target></yellow> is not accepting TPA requests right now.</red>",
                "<prefix><yellow>Your teleport protection has ended.</yellow>",
                "<prefix><green>You have <gold><seconds>s</gold> of teleport protection!</green>",
                "<prefix><gray>TPA requests are now <red>DISABLED</red>.</gray>",
                "<prefix><gray>TPA requests are now <green>ENABLED</green>.</gray>",
                "<prefix><red>Teleportation cancelled because the target location is unsafe!</red>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><gray>Teleporting in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>"
            );
        }
    }
}
