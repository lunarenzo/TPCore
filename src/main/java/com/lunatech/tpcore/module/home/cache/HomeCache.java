package com.lunatech.tpcore.module.home.cache;

import com.lunatech.tpcore.module.home.model.Home;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface HomeCache {

    void loadPlayer(UUID uuid, Map<String, Home> homes);

    void unloadPlayer(UUID uuid);

    boolean isLoaded(UUID uuid);

    Optional<Home> getHome(UUID uuid, String name);

    void putHome(UUID uuid, Home home);

    void removeHome(UUID uuid, String name);

    Map<String, Home> getHomes(UUID uuid);

    int getHomeCount(UUID uuid);

    void clear();
}
