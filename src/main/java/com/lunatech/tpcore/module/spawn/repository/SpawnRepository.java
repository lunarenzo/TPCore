package com.lunatech.tpcore.module.spawn.repository;

import com.lunatech.tpcore.module.spawn.model.SpawnLocation;

import java.util.Map;
import java.util.Optional;

public interface SpawnRepository {

    Optional<SpawnLocation> getGlobalSpawn();

    void setGlobalSpawn(SpawnLocation spawnLocation);

    void removeGlobalSpawn();

    Optional<SpawnLocation> getWorldSpawn(String worldName);

    void setWorldSpawn(String worldName, SpawnLocation spawnLocation);

    void removeWorldSpawn(String worldName);

    Map<String, SpawnLocation> getAllWorldSpawns();

    void save();

    void load();
}
