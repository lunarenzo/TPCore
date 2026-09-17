package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public record SpawnConfig(
    @Comment("Enable or disable the Spawn module completely")
    boolean enabled,

    @Comment("Warmup delay in seconds before teleporting to spawn")
    int warmupSeconds,

    @Comment("Cooldown in seconds between spawn teleports")
    int cooldownSeconds,

    @Comment("Cancel teleport warmup if the player moves")
    boolean cancelOnMove,

    @Comment("Cancel teleport warmup if the player takes damage")
    boolean cancelOnDamage,

    @Comment("Teleport player to spawn on their very first join")
    boolean spawnOnFirstJoin,

    @Comment("Teleport player to spawn on every join (Lobby/Hub mode)")
    boolean spawnOnJoin,

    @Comment("Teleport player to spawn on death/respawn")
    boolean spawnOnRespawn,

    @Comment("Override vanilla bed and anchor respawns to force spawn location")
    boolean overrideBedRespawn,

    @Comment("Automatically rescue players falling into the void and teleport to spawn")
    boolean voidFallProtection,

    @Comment("Module message strings (Alphabetically ordered)")
    SpawnMessages messages
) {
    public static SpawnConfig createDefault() {
        return new SpawnConfig(
            true,
            3,
            10,
            true,
            true,
            true,
            false,
            true,
            false,
            true,
            SpawnMessages.createDefault()
        );
    }

    @ConfigSerializable
    public record SpawnMessages(
        String cooldownActive,
        String delSpawnGlobalSuccess,
        String delSpawnWorldSuccess,
        String noSpawnSet,
        String noSpawnSetWorld,
        String onlyPlayers,
        String prefix,
        String setSpawnGlobalSuccess,
        String setSpawnWorldSuccess,
        String spawnTeleportSuccess,
        String voidRescued,
        String warmupCancelledDamage,
        String warmupCancelledMove,
        String warmupStart
    ) {
        public static SpawnMessages createDefault() {
            return new SpawnMessages(
                "<prefix><red>You must wait <gold><seconds>s</gold> before using /spawn again!</red>",
                "<prefix><green>Global spawn location has been deleted.</green>",
                "<prefix><green>Spawn location for world <yellow><world></yellow> has been deleted.</green>",
                "<prefix><red>No spawn point has been set!</red>",
                "<prefix><red>No spawn point has been set for world <yellow><world></yellow>!</red>",
                "<prefix><red>Only players can execute this command!</red>",
                "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><green>Global spawn location set to <yellow><location></yellow>.</green>",
                "<prefix><green>Spawn location for world <yellow><world></yellow> set to <yellow><location></yellow>.</green>",
                "<prefix><green>Teleported to spawn!</green>",
                "<prefix><yellow>You were saved from falling into the void and returned to spawn!</yellow>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><gray>Teleporting to spawn in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>"
            );
        }
    }
}
