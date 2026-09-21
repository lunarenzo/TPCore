package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record TpaConfig(
    @Comment("Enable or disable the TPA module completely")
    boolean enabled,

    @Comment("Timeout in seconds before an unaccepted TPA request automatically expires")
    int requestTimeoutSeconds,

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

    @Comment("Confirmation menu mode: CHAT, GUI, or DIALOG (Default: CHAT)")
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

    @Comment("Item material for Deny button")
    String guiDenyItem,

    @Comment("Item material for Background filler")
    String guiFillItem,

    @Comment("Title for Paper Dialog confirmation menu (1.21.6+)")
    String dialogTitle,

    @Comment("Body text for Paper Dialog menu")
    String dialogBodyText,

    @Comment("Accept button label for Paper Dialog menu")
    String dialogAcceptText,

    @Comment("Deny button label for Paper Dialog menu")
    String dialogDenyText,

    @Comment("Module message strings (Alphabetically ordered)")
    TpaMessages messages
) {
    public static TpaConfig createDefault() {
        return new TpaConfig(
            true, 30, 3, true, true, false, true,
            "CHAT",
            "<gradient:#00D2FF:#3A7BD5><bold>Teleport Request</bold></gradient>",
            15, 11, 13,
            "LIME_STAINED_GLASS_PANE",
            "RED_STAINED_GLASS_PANE",
            "GRAY_STAINED_GLASS_PANE",
            "<gradient:#00D2FF:#3A7BD5><bold>Teleport Confirmation</bold></gradient>",
            "<yellow><sender></yellow> sent a teleport request.\nDo you accept?",
            "<green><bold>ACCEPT</bold></green>",
            "<red><bold>DENY</bold></red>",
            TpaMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record TpaMessages(
        String alreadyHasPendingRequest,
        String multiplePendingRequests,
        String noPendingRequests,
        String playerNotOnline,
        String prefix,
        String rejectSelfTpa,
        String requestAcceptedSender,
        String requestAcceptedTarget,
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
                "<prefix><red>You have multiple requests! Specify the player name: <yellow>/tpaccept <player></yellow></red>",
                "<prefix><red>You have no active pending teleport requests!</red>",
                "<prefix><red>Player <yellow><player></yellow> is not online!</red>",
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><red>You cannot send a teleport request to yourself!</red>",
                "<prefix><yellow><target></yellow> <green>accepted your teleport request!</green>",
                "<prefix><green>Accepted teleport request from <yellow><sender></yellow>.</green>",
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
