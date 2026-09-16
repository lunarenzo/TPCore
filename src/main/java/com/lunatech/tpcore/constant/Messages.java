package com.lunatech.tpcore.constant;

public final class Messages {

    public static final String PREFIX = "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ";
    
    public static final String PLAYER_NOT_ONLINE = PREFIX + "<red>Player <yellow><player></yellow> is not online!</red>";
    public static final String REJECT_SELF_TPA = PREFIX + "<red>You cannot send a teleport request to yourself!</red>";
    
    public static final String SENDER_TPA_SENT = PREFIX + "<gray>Sent a teleport request to <yellow><target></yellow>. Expires in <gold><seconds>s</gold>.</gray>";
    public static final String SENDER_TPAHERE_SENT = PREFIX + "<gray>Sent a request for <yellow><target></yellow> to teleport to you. Expires in <gold><seconds>s</gold>.</gray>";
    
    public static final String TARGET_TPA_RECEIVED = PREFIX + "<yellow><sender></yellow> <gray>wants to teleport to you.</gray>\n" +
        PREFIX + "<green><click:run_command:'/tpaccept <sender>'><hover:show_text:'<green>Click to ACCEPT teleport request</green>'><bold>[ACCEPT]</bold></click></green>  " +
        "<red><click:run_command:'/tpdeny <sender>'><hover:show_text:'<red>Click to DENY teleport request</red>'><bold>[DENY]</bold></click></red>";
        
    public static final String TARGET_TPAHERE_RECEIVED = PREFIX + "<yellow><sender></yellow> <gray>requests you to teleport to them.</gray>\n" +
        PREFIX + "<green><click:run_command:'/tpaccept <sender>'><hover:show_text:'<green>Click to ACCEPT teleport request</green>'><bold>[ACCEPT]</bold></click></green>  " +
        "<red><click:run_command:'/tpdeny <sender>'><hover:show_text:'<red>Click to DENY teleport request</red>'><bold>[DENY]</bold></click></red>";

    public static final String NO_PENDING_REQUESTS = PREFIX + "<red>You have no active pending teleport requests!</red>";
    public static final String MULTIPLE_PENDING_REQUESTS = PREFIX + "<red>You have multiple requests! Specify the player name: <yellow>/tpaccept <player></yellow></red>";
    
    public static final String REQUEST_ACCEPTED_SENDER = PREFIX + "<yellow><target></yellow> <green>accepted your teleport request!</green>";
    public static final String REQUEST_ACCEPTED_TARGET = PREFIX + "<green>Accepted teleport request from <yellow><sender></yellow>.</green>";

    public static final String REQUEST_DENIED_SENDER = PREFIX + "<yellow><target></yellow> <red>denied your teleport request.</red>";
    public static final String REQUEST_DENIED_TARGET = PREFIX + "<red>Denied teleport request from <yellow><sender></yellow>.</red>";

    public static final String REQUEST_CANCELLED_SENDER = PREFIX + "<gray>Cancelled your teleport request to <yellow><target></yellow>.</gray>";
    public static final String REQUEST_CANCELLED_TARGET = PREFIX + "<yellow><sender></yellow> <gray>cancelled their teleport request.</gray>";

    public static final String REQUEST_EXPIRED = PREFIX + "<red>The teleport request with <yellow><player></yellow> has expired.</red>";

    public static final String WARMUP_START = PREFIX + "<gray>Teleporting in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>";
    public static final String WARMUP_CANCELLED_MOVE = PREFIX + "<red>Teleport cancelled because you moved!</red>";
    public static final String WARMUP_CANCELLED_DAMAGE = PREFIX + "<red>Teleport cancelled because you took damage!</red>";

    public static final String TOGGLE_ON = PREFIX + "<gray>TPA requests are now <green>ENABLED</green>.</gray>";
    public static final String TOGGLE_OFF = PREFIX + "<gray>TPA requests are now <red>DISABLED</red>.</gray>";
    public static final String TARGET_TOGGLED_OFF = PREFIX + "<red>Player <yellow><target></yellow> is not accepting TPA requests right now.</red>";

    private Messages() {
        throw new UnsupportedOperationException("Constant utility class cannot be instantiated.");
    }
}
