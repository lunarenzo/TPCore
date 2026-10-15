package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record WarpRuntimeConfig(boolean allowLegacyWorldNames, int safetyFreshnessMillis,
    String teleportPending, String teleportInterrupted) {
    public WarpRuntimeConfig {
        safetyFreshnessMillis = Math.max(50, Math.min(5000, safetyFreshnessMillis));
        if (teleportPending == null) teleportPending = createDefault().teleportPending();
        if (teleportInterrupted == null) teleportInterrupted = createDefault().teleportInterrupted();
    }
    public static WarpRuntimeConfig createDefault() {
        return new WarpRuntimeConfig(true, 250,
            "<prefix><yellow>Your teleport outcome is still unknown. Do not retry; ask an administrator to check the server log and payment evidence.</yellow>",
            "<prefix><yellow>Warp cancelled because another teleport moved you.</yellow>");
    }
}
