package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record TpaConfig(
    @Comment("Enable or disable the TPA module completely")
    boolean enabled,

    @Comment("Timeout in seconds before an unaccepted TPA request automatically expires")
    int requestTimeoutSeconds,

    @Comment("Warmup delay in seconds before teleporting")
    int warmupSeconds,

    @Comment("Cancel teleport warmup if the player moves > 0.5 blocks")
    boolean cancelOnMove,

    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Comment("Allow players to send TPA requests to themselves")
    boolean allowSelfTpa
) {
    public static TpaConfig createDefault() {
        return new TpaConfig(true, 30, 3, true, true, false);
    }
}
