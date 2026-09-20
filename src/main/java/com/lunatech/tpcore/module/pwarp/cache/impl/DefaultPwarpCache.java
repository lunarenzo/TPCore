package com.lunatech.tpcore.module.pwarp.cache.impl;

import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultPwarpCache implements PwarpCache {

    private final Map<String, Pwarp> warpMap = new ConcurrentHashMap<>();

    @Override
    public void put(Pwarp pwarp) {
        if (pwarp != null) {
            this.warpMap.put(pwarp.name().toLowerCase(Locale.ROOT), pwarp);
        }
    }

    @Override
    public void remove(String name) {
        if (name != null) {
            this.warpMap.remove(name.toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public Optional<Pwarp> getByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.warpMap.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public List<Pwarp> getByOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return List.of();
        }
        List<Pwarp> list = new ArrayList<>();
        for (Pwarp pwarp : this.warpMap.values()) {
            if (pwarp.ownerUuid().equals(ownerUuid)) {
                list.add(pwarp);
            }
        }
        list.sort(Comparator.comparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(list);
    }

    @Override
    public List<Pwarp> getAllPublic() {
        List<Pwarp> list = new ArrayList<>();
        for (Pwarp pwarp : this.warpMap.values()) {
            if (!pwarp.isPrivate()) {
                list.add(pwarp);
            }
        }
        list.sort(Comparator.comparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(list);
    }

    @Override
    public List<Pwarp> getAll() {
        List<Pwarp> list = new ArrayList<>(this.warpMap.values());
        list.sort(Comparator.comparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(list);
    }

    @Override
    public int countByOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return 0;
        }
        int count = 0;
        for (Pwarp pwarp : this.warpMap.values()) {
            if (pwarp.ownerUuid().equals(ownerUuid)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int size() {
        return this.warpMap.size();
    }

    @Override
    public void clear() {
        this.warpMap.clear();
    }
}
