package com.lunatech.tpcore.module.pwarp.model;

import java.util.Locale;

public enum PwarpAccessType {
    PUBLIC("Public"),
    PRIVATE("Private"),
    PASSWORD("Password Protected"),
    WHITELIST("Whitelist Only"),
    BLACKLIST("Blacklist Only");

    private final String displayName;

    PwarpAccessType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PwarpAccessType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return PUBLIC;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PUBLIC;
        }
    }
}
