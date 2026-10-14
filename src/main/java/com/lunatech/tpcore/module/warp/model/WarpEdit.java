package com.lunatech.tpcore.module.warp.model;

import java.util.UUID;

/** Immutable edit requests; relocation contains no live world or location reference. */
public sealed interface WarpEdit {
    record Rename(String name) implements WarpEdit {}
    record Relocate(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) implements WarpEdit {}
    record Category(String category) implements WarpEdit {}
    record Password(String value) implements WarpEdit {
        @Override public String toString() { return "Password[redacted]"; }
    }
    record ClearPassword() implements WarpEdit {}
    record Permission(boolean required) implements WarpEdit {}
    record Close(String reason) implements WarpEdit {}
    record Open() implements WarpEdit {}
    record Listing(boolean unlisted) implements WarpEdit {}
}
