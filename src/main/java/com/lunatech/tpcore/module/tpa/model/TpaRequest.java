package com.lunatech.tpcore.module.tpa.model;

import java.util.UUID;

public record TpaRequest(
    UUID senderId,
    UUID targetId,
    TpaType type,
    long createdAtEpochMs
) {
    public boolean isExpired(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return false;
        }
        return (System.currentTimeMillis() - this.createdAtEpochMs) > (timeoutSeconds * 1000L);
    }
}
