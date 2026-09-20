package com.lunatech.tpcore.module.pwarp.model;

/**
 * Supported sorting modes for displaying player warps in Chest GUIs.
 */
public enum PwarpSorting {
    MOST_VISITED("Most Visited"),
    HIGHEST_RATED("Highest Rated"),
    NEWEST("Newest First"),
    OLDEST("Oldest First"),
    ALPHABETICAL("Alphabetical (A-Z)");

    private final String displayName;

    PwarpSorting(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public PwarpSorting next() {
        PwarpSorting[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
