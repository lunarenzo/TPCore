package com.lunatech.tpcore.module.spawn.service;

import com.lunatech.tpcore.config.model.SpawnConfig;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface SpawnService {

    void teleportToSpawn(Player player, String optionalWorldName);

    void teleportOtherToSpawn(CommandSender sender, Player target, String optionalWorldName);

    void setGlobalSpawn(Player player);

    void setWorldSpawn(Player player, String worldName);

    void deleteGlobalSpawn(Player player);

    void deleteWorldSpawn(Player player, String worldName);

    Optional<Location> getEffectiveSpawnLocation(String worldName);

    boolean hasActiveWarmup(UUID playerId);

    void handlePlayerMove(Player player);

    void handlePlayerDamage(UUID playerId);

    void handlePlayerTeleport(UUID playerId);

    void handlePlayerQuit(UUID playerId);

    boolean handlePlayerProtectionDamage(Player victim, Player attacker, boolean isPvp);

    long getTeleportProtectionStartTime(UUID playerId);

    void rescueFromVoid(Player player);

    void updateConfig(SpawnConfig newConfig);

    void shutdown();
}
