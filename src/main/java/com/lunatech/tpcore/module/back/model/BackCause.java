package com.lunatech.tpcore.module.back.model;

public enum BackCause {
    TELEPORT,
    DEATH,
    PORTAL,
    UNKNOWN;

    public boolean isDeath() {
        return this == DEATH;
    }

    public boolean isPortal() {
        return this == PORTAL;
    }

    public boolean isTeleport() {
        return this == TELEPORT;
    }
}

