package com.lunatech.tpcore.module.pwarp.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable data record representing a 1-5 star player rating for a player warp.
 */
public record PwarpRating(
    int warpId,
    UUID playerUuid,
    int stars,
    long createdAt
) {
    public PwarpRating {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("Stars must be between 1 and 5 inclusive, got: " + stars);
        }
    }
}
