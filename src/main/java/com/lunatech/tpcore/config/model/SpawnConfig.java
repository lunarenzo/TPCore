package com.lunatech.tpcore.config.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.Locale;

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

    @Comment("Require safe landing location when teleporting to spawn")
    boolean requireSafeLocation,

    @Comment("Enable Vault economy integration for spawn teleport costs")
    boolean economyEnabled,

    @Comment("Cost charged to player when teleporting to spawn")
    double spawnCost,

    @Comment("Charge timing mode: CHARGE_ON_START or CHARGE_ON_SUCCESS")
    String chargeTiming,

    @Comment("Refund teleportation cost if warmup is cancelled (by movement, damage, or teleport)")
    boolean refundOnCancel,

    @Comment("Enable BossBar progress indicator during warmup")
    boolean enableBossbar,

    @Comment("BossBar color (PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE)")
    String bossbarColor,

    @Comment("BossBar overlay style (PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20)")
    String bossbarOverlay,

    @Comment("BossBar title format string")
    String bossbarFormat,

    @Comment("Enable ActionBar countdown text during warmup")
    boolean enableActionBar,

    @Comment("ActionBar format string")
    String actionBarFormat,

    @Comment("Enable on-screen Title and Subtitle countdown during warmup")
    boolean enableTitle,

    @Comment("Title format string")
    String titleFormat,

    @Comment("Subtitle format string")
    String subtitleFormat,

    @Comment("Enable audio sound effects for ticks, success, and cancellations")
    boolean enableSounds,

    @Comment("Key of the sound played on each warmup countdown tick")
    String tickSound,

    @Comment("Volume of the tick sound")
    double tickSoundVolume,

    @Comment("Key of the sound played on successful teleport")
    String teleportSound,

    @Comment("Volume of the teleport sound")
    double teleportSoundVolume,

    @Comment("Pitch of the teleport sound")
    double teleportSoundPitch,

    @Comment("Key of the sound played when warmup is cancelled")
    String cancelSound,

    @Comment("Volume of the cancellation sound")
    double cancelSoundVolume,

    @Comment("Pitch of the cancellation sound")
    double cancelSoundPitch,

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
            true,
            false,
            0.0,
            "CHARGE_ON_START",
            true,
            true,
            "YELLOW",
            "PROGRESS",
            "<prefix><gray>Teleporting to spawn in <gold><seconds></gold>s...</gray>",
            true,
            "<gray>Teleporting to spawn in <gold><seconds></gold> seconds...</gray>",
            false,
            "<gold><seconds></gold>",
            "<gray>Teleporting to spawn...</gray>",
            true,
            "minecraft:block.note_block.hat",
            0.6,
            "minecraft:entity.enderman.teleport",
            0.8,
            1.0,
            "minecraft:block.note_block.bass",
            0.8,
            0.5,
            SpawnMessages.createDefault()
        );
    }

    public String getNormalizedChargeTiming() {
        if (this.chargeTiming != null && !this.chargeTiming.isBlank()) {
            return this.chargeTiming.trim().toUpperCase(Locale.ROOT);
        }
        return "CHARGE_ON_START";
    }

    @ConfigSerializable
    public record SpawnMessages(
        String cooldownActive,
        String costDeducted,
        String costRefunded,
        String delSpawnGlobalSuccess,
        String delSpawnWorldSuccess,
        String insufficientFunds,
        String mustBeInTargetWorld,
        String noSpawnSet,
        String noSpawnSetWorld,
        String onlyPlayers,
        String prefix,
        String setSpawnGlobalSuccess,
        String setSpawnWorldSuccess,
        String spawnTeleportSuccess,
        String teleportFailed,
        String voidRescued,
        String warmupCancelledDamage,
        String warmupCancelledMove,
        String warmupCancelledTeleport,
        String warmupStart
    ) {
        public static SpawnMessages createDefault() {
            return new SpawnMessages(
                "<prefix><red>You must wait <gold><seconds>s</gold> before using /spawn again!</red>",
                "<prefix><green>Spawn teleport fee of <yellow><cost></yellow> has been deducted from your account.</green>",
                "<prefix><yellow>Spawn teleport fee of <gold><cost></gold> has been refunded.</yellow>",
                "<prefix><green>Global spawn location has been deleted.</green>",
                "<prefix><green>Spawn location for world <yellow><world></yellow> has been deleted.</green>",
                "<prefix><red>You need <yellow><cost></yellow> to teleport to spawn! Your balance: <gold><balance></gold>.</red>",
                "<prefix><red>You must be in world <yellow><world></yellow> to set its spawn point!</red>",
                "<prefix><red>No spawn point has been set!</red>",
                "<prefix><red>No spawn point has been set for world <yellow><world></yellow>!</red>",
                "<prefix><red>Only players can execute this command!</red>",
                "<prefix><gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ",
                "<prefix><green>Global spawn location set to <yellow><location></yellow>.</green>",
                "<prefix><green>Spawn location for world <yellow><world></yellow> set to <yellow><location></yellow>.</green>",
                "<prefix><green>Teleported to spawn!</green>",
                "<prefix><red>Could not find a safe spawn location or teleportation failed!</red>",
                "<prefix><yellow>You were saved from falling into the void and returned to spawn!</yellow>",
                "<prefix><red>Teleport cancelled because you took damage!</red>",
                "<prefix><red>Teleport cancelled because you moved!</red>",
                "<prefix><red>Teleport cancelled because you were teleported!</red>",
                "<prefix><gray>Teleporting to spawn in <gold><seconds></gold> seconds. <red>Do not move or take damage!</red></gray>"
            );
        }
    }
}
