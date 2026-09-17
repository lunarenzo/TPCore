package com.lunatech.tpcore.module.home.cache.impl;

import com.lunatech.tpcore.module.home.cache.HomeCache;
import com.lunatech.tpcore.module.home.model.Home;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultHomeCache implements HomeCache {

    private final Map<UUID, Map<String, Home>> cache = new ConcurrentHashMap<>();

    @Override
    public void loadPlayer(UUID uuid, Map<String, Home> homes) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        Map<String, Home> userMap = new ConcurrentHashMap<>();
        if (homes != null) {
            homes.forEach((key, home) -> userMap.put(key.toLowerCase(), home));
        }
        cache.put(uuid, userMap);
    }

    @Override
    public void unloadPlayer(UUID uuid) {
        if (uuid != null) {
            cache.remove(uuid);
        }
    }

    @Override
    public boolean isLoaded(UUID uuid) {
        return uuid != null && cache.containsKey(uuid);
    }

    @Override
    public Optional<Home> getHome(UUID uuid, String name) {
        if (uuid == null || name == null) {
            return Optional.empty();
        }
        Map<String, Home> userHomes = cache.get(uuid);
        if (userHomes == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userHomes.get(name.toLowerCase()));
    }

    @Override
    public void putHome(UUID uuid, Home home) {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        Objects.requireNonNull(home, "home cannot be null");
        cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(home.name().toLowerCase(), home);
    }

    @Override
    public void removeHome(UUID uuid, String name) {
        if (uuid == null || name == null) {
            return;
        }
        Map<String, Home> userHomes = cache.get(uuid);
        if (userHomes != null) {
            userHomes.remove(name.toLowerCase());
        }
    }

    @Override
    public Map<String, Home> getHomes(UUID uuid) {
        if (uuid == null) {
            return Collections.emptyMap();
        }
        Map<String, Home> userHomes = cache.get(uuid);
        return userHomes != null ? Map.copyOf(userHomes) : Collections.emptyMap();
    }

    @Override
    public int getHomeCount(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        Map<String, Home> userHomes = cache.get(uuid);
        return userHomes != null ? userHomes.size() : 0;
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
