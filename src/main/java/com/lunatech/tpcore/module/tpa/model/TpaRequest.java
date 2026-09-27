package com.lunatech.tpcore.module.tpa.model;

import java.util.UUID;

public record TpaRequest(
    UUID senderId,
    UUID targetId,
    TpaType type,
    long createdAtEpochMs,
    double cost
) {
    public TpaRequest(UUID senderId, UUID targetId, TpaType type, long createdAtEpochMs) {
        this(senderId, targetId, type, createdAtEpochMs, 0.0);
    }
    public boolean isExpired(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return false;
        }
        return (System.currentTimeMillis() - this.createdAtEpochMs) > (timeoutSeconds * 1000L);
    }
}
