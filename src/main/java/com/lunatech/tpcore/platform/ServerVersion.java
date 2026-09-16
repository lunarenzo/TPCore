package com.lunatech.tpcore.platform;

import org.bukkit.Bukkit;

public final class ServerVersion {

    public static final int MAJOR;
    public static final int MINOR;
    public static final int PATCH;
    public static final boolean IS_DATA_COMPONENTS;
    public static final boolean IS_PDC_DIRECT;
    public static final boolean IS_YEAR_VERSIONED;

    static {
        String raw = Bukkit.getBukkitVersion().split("-")[0];
        String[] parts = raw.split("\\.");
        int major = 1;
        int minor = 0;
        int patch = 0;

        try {
            major = Integer.parseInt(parts[0]);
            minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        } catch (NumberFormatException ignored) {
        }

        MAJOR = major;
        MINOR = minor;
        PATCH = patch;

        IS_YEAR_VERSIONED = MAJOR >= 26;
        IS_DATA_COMPONENTS = IS_YEAR_VERSIONED || (MAJOR == 1 && (MINOR > 20 || (MINOR == 20 && PATCH >= 5)));
        IS_PDC_DIRECT = IS_YEAR_VERSIONED || (MAJOR == 1 && (MINOR > 21 || (MINOR == 21 && PATCH >= 4)));
    }

    private ServerVersion() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static boolean isAtLeast(int major, int minor) {
        if (IS_YEAR_VERSIONED) {
            return true;
        }
        return MAJOR > major || (MAJOR == major && MINOR >= minor);
    }
}
