package com.lunatech.tpcore.module.warp.model;

import java.util.Objects;
import java.util.UUID;

/** A durable recovery entry. All metadata is immutable; no live server objects are retained. */
public record DeletedWarp(UUID id, Warp warp, long deletedAt, UUID deletedBy) {
    public DeletedWarp {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(warp, "warp");
    }
}
