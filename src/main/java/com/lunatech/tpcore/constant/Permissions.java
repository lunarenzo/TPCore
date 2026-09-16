package com.lunatech.tpcore.constant;

public final class Permissions {

    public static final String TPA_USE = "tpcore.tpa.use";
    public static final String TPA_HERE = "tpcore.tpa.here";
    public static final String TPA_ACCEPT = "tpcore.tpa.accept";
    public static final String TPA_DENY = "tpcore.tpa.deny";
    public static final String TPA_CANCEL = "tpcore.tpa.cancel";
    public static final String TPA_TOGGLE = "tpcore.tpa.toggle";
    public static final String TPA_BYPASS_WARMUP = "tpcore.tpa.bypass.warmup";
    public static final String ADMIN_RELOAD = "tpcore.admin.reload";

    private Permissions() {
        throw new UnsupportedOperationException("Constant utility class cannot be instantiated.");
    }
}
