package com.lunatech.tpcore.module.pwarp.config;

import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record PwarpConfig(
    @Comment("Enable or disable the PlayerWarps module completely")
    boolean enabled,

    @Comment("Maximum name length allowed for a player warp")
    int maxNameLength,

    @Comment("Default icon material for newly created player warps")
    String defaultIconMaterial,

    @Comment("Dynamic pwarp creation limits mapped to permissions (tpcore.pwarp.limit.<key>)")
    Map<String, Integer> warpLimits,

    @Comment("Per-world pwarp configurations")
    Map<String, PwarpWorldConfig> worldConfigs,

    @Comment("Player-facing MiniMessage formatted string messages")
    PwarpMessages messages
) {
    public PwarpConfig {
        warpLimits = warpLimits != null ? Map.copyOf(warpLimits) : Map.of();
        worldConfigs = worldConfigs != null ? Map.copyOf(worldConfigs) : Map.of();
        defaultIconMaterial = (defaultIconMaterial != null && !defaultIconMaterial.isBlank()) ? defaultIconMaterial : "PLAYER_HEAD";
    }

    public static PwarpConfig createDefault() {
        return new PwarpConfig(
            true,
            16,
            "PLAYER_HEAD",
            Map.of(
                "default", 2,
                "vip", 5,
                "mvp", 10,
                "staff", 50
            ),
            Map.of("world", PwarpWorldConfig.createDefault()),
            PwarpMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record PwarpMessages(
        String prefix,
        String disabled,
        String noPermission,
        String worldDisabled,
        String cooldownActive,
        String warmupStarted,
        String warmupCancelled,
        String searchFailed,
        String teleportSuccess,
        String teleportFailed,
        String onlyPlayers,
        String warpNotFound,
        String worldNotFound,
        String warpExists,
        String setSuccess,
        String delSuccess,
        String limitReached,
        String cannotUseMounted,
        String reloadSuccess
    ) {
        public static PwarpMessages createDefault() {
            return new PwarpMessages(
                "<gradient:#FFAA00:#FF5500>[Pwarp]</gradient> ",
                "<red>PlayerWarps module is currently disabled.</red>",
                "<red>You do not have permission to use PlayerWarps.</red>",
                "<red>PlayerWarps are disabled in world '<world>'.</red>",
                "<red>Please wait <seconds> seconds before teleporting again.</red>",
                "<green>Teleporting to pwarp <yellow><warp></yellow> in <seconds> seconds. Do not move or take damage!</green>",
                "<red>Teleport cancelled due to movement, damage, or world change.</red>",
                "<red>Failed to locate safe ground target at destination.</red>",
                "<green>Teleported to player warp <yellow><warp></yellow>!</green>",
                "<red>Teleport failed due to unsafe terrain target.</red>",
                "<red>Only players can execute player warp commands.</red>",
                "<red>Player warp '<warp>' was not found!</red>",
                "<red>World '<world>' does not exist or is not loaded.</red>",
                "<red>A player warp named '<warp>' already exists!</red>",
                "<green>Successfully set player warp <yellow><warp></yellow>!</green>",
                "<green>Successfully deleted player warp <yellow><warp></yellow>!</green>",
                "<red>You have reached your limit of <max> player warps!</red>",
                "<red>You cannot use player warps while riding a mount or vehicle!</red>",
                "<green>PlayerWarps module configuration reloaded successfully.</green>"
            );
        }
    }
}
