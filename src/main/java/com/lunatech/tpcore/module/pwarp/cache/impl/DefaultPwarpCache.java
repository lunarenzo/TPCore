package com.lunatech.tpcore.module.pwarp.cache.impl;

import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance lock-free in-memory cache for PlayerWarps.
 * Maintains pre-sorted immutable read indices for O(1) GUI page slicing and tab-completion.
 */
public final class DefaultPwarpCache implements PwarpCache {

    private final Map<String, Pwarp> warpMap = new ConcurrentHashMap<>();
    private volatile List<Pwarp> sortedPublicWarps = List.of();
    private volatile List<Pwarp> sortedAllWarps = List.of();

    @Override
    public void put(Pwarp pwarp) {
        if (pwarp != null) {
            this.warpMap.put(pwarp.name().toLowerCase(Locale.ROOT), pwarp);
            rebuildSortedIndices();
        }
    }

    @Override
    public void remove(String name) {
        if (name != null && this.warpMap.remove(name.toLowerCase(Locale.ROOT)) != null) {
            rebuildSortedIndices();
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
        for (Pwarp pwarp : this.sortedAllWarps) {
            if (pwarp.ownerUuid().equals(ownerUuid)) {
                list.add(pwarp);
            }
        }
        return List.copyOf(list);
    }

    @Override
    public List<Pwarp> getAllPublic() {
        return this.sortedPublicWarps;
    }

    @Override
    public List<Pwarp> getAll() {
        return this.sortedAllWarps;
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
        this.sortedPublicWarps = List.of();
        this.sortedAllWarps = List.of();
    }

    private synchronized void rebuildSortedIndices() {
        List<Pwarp> allList = new ArrayList<>(this.warpMap.values());
        allList.sort(Comparator.comparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        this.sortedAllWarps = List.copyOf(allList);

        List<Pwarp> publicList = new ArrayList<>();
        for (Pwarp pwarp : allList) {
            if (!pwarp.isPrivate()) {
                publicList.add(pwarp);
            }
        }
        this.sortedPublicWarps = List.copyOf(publicList);
    }
}
