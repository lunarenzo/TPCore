package com.lunatech.tpcore.module.spawn.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface SpawnService {

    void teleportToSpawn(Player player, String optionalWorldName);

    void setGlobalSpawn(Player player);

    void setWorldSpawn(Player player, String worldName);

    void deleteGlobalSpawn(Player player);

    void deleteWorldSpawn(Player player, String worldName);

    Optional<Location> getEffectiveSpawnLocation(String worldName);

    void handlePlayerMove(Player player);

    void handlePlayerDamage(UUID playerId);

    void handlePlayerQuit(UUID playerId);

    void rescueFromVoid(Player player);

    void shutdown();
}
