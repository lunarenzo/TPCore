package com.lunatech.tpcore.module.warp.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Versioned metadata bound to a block position and a warp creation fingerprint, not display text. */
public record WarpSignBinding(UUID worldId, int x, int y, int z, String name, long createdAt, UUID creator) {
    public WarpSignBinding {
        Objects.requireNonNull(worldId);
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid bound warp name");
        name = name.toLowerCase(Locale.ROOT);
    }

    public String encode() {
        return "1|" + worldId + "|" + x + "|" + y + "|" + z + "|" + name + "|" + createdAt + "|" + (creator == null ? "" : creator);
    }

    public static Optional<WarpSignBinding> decode(String raw) {
        if (raw == null || raw.length() > 256) return Optional.empty();
        String[] fields = raw.split("\\|", -1);
        if (fields.length != 8 || !fields[0].equals("1")) return Optional.empty();
        try {
            return Optional.of(new WarpSignBinding(UUID.fromString(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                Integer.parseInt(fields[4]), fields[5], Long.parseLong(fields[6]), fields[7].isEmpty() ? null : UUID.fromString(fields[7])));
        } catch (IllegalArgumentException failure) { return Optional.empty(); }
    }

    public boolean matches(Warp warp) {
        return warp != null && name.equalsIgnoreCase(warp.name()) && createdAt == warp.createdAt() && Objects.equals(creator, warp.creatorUuid());
    }

    public boolean at(UUID world, int blockX, int blockY, int blockZ) {
        return worldId.equals(world) && x == blockX && y == blockY && z == blockZ;
    }
}
