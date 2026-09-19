package com.lunatech.tpcore.module.rtp.config;

import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record RtpConfig(
    boolean enabled,
    int bufferCapacity,
    double maxMsptThreshold,
    int demandWindowMinutes,
    Map<String, RtpWorldConfig> worldConfigs,
    RtpMessages messages
) {
    public RtpConfig {
        worldConfigs = worldConfigs != null ? Map.copyOf(worldConfigs) : Map.of();
    }

    public static RtpConfig createDefault() {
        RtpWorldConfig defaultWorld = new RtpWorldConfig(
            "world",
            true,
            100,
            5000,
            0,
            0,
            "CIRCLE",
            List.of("OCEAN", "DEEP_OCEAN", "WARM_OCEAN", "LUKEWARM_OCEAN", "COLD_OCEAN", "DEEP_COLD_OCEAN", "FROZEN_OCEAN", "DEEP_FROZEN_OCEAN"),
            List.of(),
            30,
            60,
            0,
            0.0
        );

        return new RtpConfig(
            true,
            20,
            45.0,
            15,
            Map.of("world", defaultWorld),
            RtpMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record RtpMessages(
        String prefix,
        String disabled,
        String noPermission,
        String worldDisabled,
        String cooldownActive,
        String warmupStarted,
        String warmupAlreadyActive,
        String warmupCancelled,
        String queueWarmingUp,
        String searchFailed,
        String teleportSuccess,
        String teleportFailed,
        String onlyPlayers,
        String worldNotFound,
        String reloadSuccess
    ) {
        public static RtpMessages createDefault() {
            return new RtpMessages(
                "<gradient:#00FFAA:#00AAFF>[RTP]</gradient> ",
                "<red>Random teleportation is currently disabled.</red>",
                "<red>You do not have permission to use random teleportation.</red>",
                "<red>Random teleportation is disabled in world '<world>'.</red>",
                "<red>Please wait <seconds> seconds before using RTP again.</red>",
                "<green>Teleporting in <seconds> seconds. Do not move or take damage!</green>",
                "<red>You are already warming up for a teleport!</red>",
                "<red>Teleport cancelled due to movement or damage.</red>",
                "<yellow>RTP queue is warming up candidates, please try again in a few seconds.</yellow>",
                "<red>Failed to find a safe location after multiple retries. Try again later.</red>",
                "<green>Teleported to <x>, <y>, <z> in <world>!</green>",
                "<red>Teleport failed due to unsafe terrain target.</red>",
                "<red>Only players can execute random teleportation.</red>",
                "<red>World '<world>' does not exist or is not loaded.</red>",
                "<green>RTP module configuration reloaded successfully.</green>"
            );
        }
    }
}
