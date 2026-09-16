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

    @Comment("Module message strings")
    TpaMessages messages
) {
    public static TpaConfig createDefault() {
        return new TpaConfig(true, 30, 3, true, true, false, TpaMessages.createDefault());
    }

    @ConfigSerializable
    public record TpaMessages(
        String prefix,
        String playerNotOnline,
        String rejectSelfTpa,
        String senderTpaSent,
        String senderTpaHereSent,
        String targetTpaReceived,
        String targetTpaHereReceived,
        String noPendingRequests,
        String multiplePendingRequests,
        String requestAcceptedSender,
        String requestAcceptedTarget,
        String requestDeniedSender,
        String requestDeniedTarget,
        String requestCancelledSender,
        String requestCancelledTarget,
        String requestExpired,
        String warmupStart,
        String warmupCancelledMove,
        String warmupCancelledDamage,
        String toggleOn,
        String toggleOff,
        String targetToggledOff
    ) {
        public static TpaMessages createDefault() {
            return new TpaMessages(
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><red>Player <yellow><player></yellow> is not online!</red>",
                "<prefix><red>You cannot send a teleport request to yourself!</red>",
                "<prefix><gray>Sent a teleport request to <yellow><target></yellow>. Expires in <gold><seconds>s</gold>.</gray>",
                "<prefix><gray>Sent a request for <yellow><target></yellow> to teleport to you. Expires in <gold><seconds>s</gold>.</gray>",
                "<prefix><yellow><sender></yellow> <gray>wants to teleport to you.</gray>\n<prefix><green><click:run_command:'/tpaccept <sender>'><hover:show_text:'<green>Click to ACCEPT teleport request</green>'><bold>[ACCEPT]</bold></click></green>  <red><click:run_command:'/tpdeny <sender>'><hover:show_text:'<red>Click to DENY teleport request</red>'><bold>[DENY]</bold></click></red>",
                "<prefix><yellow><sender></yellow> <gray>requests you to teleport to them.</gray>\n<prefix><green><click:run_command:'/tpaccept <sender>'><hover:show_text:'<green>Click to ACCEPT teleport request</green>'><bold>[ACCEPT]</bold></click></green>  <red><click:run_command:'/tpdeny <sender>'><hover:show_text:'<red>Click to DENY teleport request</red>'><bold>[DENY]</bold></click></red>",
                "<prefix><red>You have no active pending teleport requests!</red>",
                "<prefix><red>You have multiple requests! Specify the player name: <yellow>/tpaccept <player></yellow></red>",
                "<prefix><yellow><target></yellow> <green>accepted your teleport request!</green>",
                "<prefix><green>Accepted teleport request from <yellow><sender></yellow>.</green>",
                "<prefix><yellow><target></yellow> <red>denied your teleport request.</red>",
                "<prefix><red>Denied teleport request from <yellow><sender></yellow>.</red>",
                "<prefix><gray>Cancelled your teleport request to <yellow><target></yellow>.</gray>",
                "<prefix><yellow><sender></yellow> <gray>cancelled their teleport request.</gray>",
                "<prefix><red>The teleport request with <yellow><player></yellow> has expired.</red>",
                "<prefix><gray>Teleporting in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><gray>TPA requests are now <green>ENABLED</green>.</gray>",
                "<prefix><gray>TPA requests are now <red>DISABLED</red>.</gray>",
                "<prefix><red>Player <yellow><target></yellow> is not accepting TPA requests right now.</red>"
            );
        }
    }
}
