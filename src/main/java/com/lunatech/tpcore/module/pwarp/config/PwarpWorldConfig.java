package com.lunatech.tpcore.module.pwarp.config;

import java.util.Objects;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record PwarpWorldConfig(
    @Comment("Enable or disable PlayerWarps creation in this specific world")
    boolean enabled,

    @Comment("Warmup delay in seconds before teleporting to a pwarp")
    int warmupSeconds,

    @Comment("Cooldown delay in seconds between pwarp teleports")
    int cooldownSeconds,

    @Comment("Cancel teleport warmup if the player moves")
    boolean cancelOnMove,

    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Comment("Allow teleporting while riding a mount or vehicle")
    boolean allowMounted
) {
    public PwarpWorldConfig {
        warmupSeconds = Math.max(0, warmupSeconds);
        cooldownSeconds = Math.max(0, cooldownSeconds);
    }

    public static PwarpWorldConfig createDefault() {
        return new PwarpWorldConfig(true, 3, 10, true, true, false);
    }
}
