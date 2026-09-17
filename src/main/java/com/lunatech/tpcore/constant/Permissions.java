package com.lunatech.tpcore.constant;

public final class Permissions {

    public static final String TPA_USE = "tpcore.tpa.use";
    public static final String TPA_HERE = "tpcore.tpa.here";
    public static final String TPA_ACCEPT = "tpcore.tpa.accept";
    public static final String TPA_DENY = "tpcore.tpa.deny";
    public static final String TPA_CANCEL = "tpcore.tpa.cancel";
    public static final String TPA_TOGGLE = "tpcore.tpa.toggle";
    public static final String TPA_BYPASS_WARMUP = "tpcore.tpa.bypass.warmup";
    public static final String SPAWN_USE = "tpcore.spawn.use";
    public static final String SPAWN_SET = "tpcore.spawn.set";
    public static final String SPAWN_DEL = "tpcore.spawn.del";
    public static final String SPAWN_BYPASS = "tpcore.spawn.bypass";
    public static final String HOME_USE = "tpcore.home.use";
    public static final String HOME_SET = "tpcore.home.set";
    public static final String HOME_DEL = "tpcore.home.del";
    public static final String HOME_LIST = "tpcore.home.list";
    public static final String HOME_SHARE = "tpcore.home.share";
    public static final String HOME_OTHER = "tpcore.home.other";
    public static final String HOME_BYPASS_WARMUP = "tpcore.home.bypass.warmup";
    public static final String HOME_BYPASS_COOLDOWN = "tpcore.home.bypass.cooldown";
    public static final String HOME_BYPASS_LIMIT = "tpcore.home.bypass.limit";
    public static final String HOME_ADMIN_BENCHMARK = "tpcore.home.admin.benchmark";
    public static final String ADMIN_RELOAD = "tpcore.admin.reload";

    private Permissions() {
        throw new UnsupportedOperationException("Constant utility class cannot be instantiated.");
    }
}
