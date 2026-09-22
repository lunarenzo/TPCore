package com.lunatech.tpcore.module.tpa.model;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public record TpaUserSettings(
    boolean toggledOff,
    Set<UUID> blockedPlayers
) {
    public static TpaUserSettings createDefault() {
        return new TpaUserSettings(false, Collections.emptySet());
    }

    public boolean isBlocked(UUID targetId) {
        return this.blockedPlayers != null && this.blockedPlayers.contains(targetId);
    }
}
