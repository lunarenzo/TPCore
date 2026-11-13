package com.lunatech.tpcore.module.rtp.config;

import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record RtpConfig(
    boolean enabled,
    int bufferCapacity,
    double maxMsptThreshold,
    int demandWindowMinutes,
    Map<String, RtpWorldConfig> worldConfigs,
    RtpMessages messages,
    Runtime runtime,
    RtpEconomyConfig economy
) {
    @ConfigSerializable
    public record Runtime(int maxRequests, int timeoutSeconds, int maxCooldowns, boolean generateChunks,
                          int storageQueueCapacity, int cacheRetentionPerWorld, int cacheMaxAgeMinutes) {
        public Runtime {
            if (maxRequests < 1 || maxRequests > 64 || timeoutSeconds < 1 || timeoutSeconds > 120
                || maxCooldowns < maxRequests || maxCooldowns > 32768 || storageQueueCapacity < 1 || storageQueueCapacity > 512
                || cacheRetentionPerWorld < 1 || cacheRetentionPerWorld > 1024 || cacheMaxAgeMinutes < 1 || cacheMaxAgeMinutes > 10080)
                throw new IllegalArgumentException("Invalid RTP runtime bounds");
        }
        public static Runtime defaults() { return new Runtime(16, 20, 2048, true, 64, 64, 60); }
    }

    public RtpConfig(boolean enabled, int bufferCapacity, double maxMsptThreshold, int demandWindowMinutes,
                     Map<String, RtpWorldConfig> worldConfigs, RtpMessages messages) {
        this(enabled, bufferCapacity, maxMsptThreshold, demandWindowMinutes, worldConfigs, messages, Runtime.defaults(), RtpEconomyConfig.createDefault());
    }

    public RtpConfig(boolean enabled, int bufferCapacity, double maxMsptThreshold, int demandWindowMinutes,
                     Map<String, RtpWorldConfig> worldConfigs, RtpMessages messages, Runtime runtime) {
        this(enabled, bufferCapacity, maxMsptThreshold, demandWindowMinutes, worldConfigs, messages, runtime, RtpEconomyConfig.createDefault());
    }

    public RtpConfig {
        if (bufferCapacity < 2 || bufferCapacity > 32 || !Double.isFinite(maxMsptThreshold) || maxMsptThreshold < 1
            || demandWindowMinutes < 1 || demandWindowMinutes > 1440 || (worldConfigs != null && worldConfigs.size() > 64))
            throw new IllegalArgumentException("Invalid RTP configuration bounds");
        economy = economy == null ? RtpEconomyConfig.createDefault() : economy;
        runtime = runtime == null ? Runtime.defaults() : runtime;
        var normalized = new HashMap<String, RtpWorldConfig>();
        if (worldConfigs != null) worldConfigs.forEach((name, policy) -> {
            if (normalized.put(name.toLowerCase(Locale.ROOT), policy) != null)
                throw new IllegalArgumentException("Duplicate RTP world: " + name);
        });
        worldConfigs = Map.copyOf(normalized);
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
            0.0,
            false,
            false
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
        String reloadSuccess,
        String cannotUseMounted,
        String busy,
        String timedOut,
        String outcomeUnknown,
        String reloadFailed
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
                "<green>RTP module configuration reloaded successfully.</green>",
                "<red>You cannot use random teleportation while riding a mount or vehicle!</red>",
                "<yellow>RTP is busy or you already have a pending request. Try again shortly.</yellow>",
                "<yellow>Your RTP search timed out before departure.</yellow>",
                "<yellow>Your teleport was dispatched but its arrival is unconfirmed. Wait before trying again.</yellow>",
                "<red>RTP reload failed. The last valid configuration was retained.</red>"
            );
        }
    }
}
