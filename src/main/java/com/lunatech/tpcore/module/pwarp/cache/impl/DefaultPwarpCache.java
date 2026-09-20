package com.lunatech.tpcore.module.pwarp.cache.impl;

import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.model.PwarpSorting;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance lock-free in-memory cache for PlayerWarps.
 * Maintains pre-sorted immutable read indices for O(1) GUI page slicing across all sorting algorithms.
 */
public final class DefaultPwarpCache implements PwarpCache {

    private final Map<String, Pwarp> warpMap = new ConcurrentHashMap<>();
    private final Map<PwarpSorting, List<Pwarp>> sortedPublicIndices = new EnumMap<>(PwarpSorting.class);
    private volatile List<Pwarp> sortedAllWarps = List.of();

    public DefaultPwarpCache() {
        for (PwarpSorting sorting : PwarpSorting.values()) {
            this.sortedPublicIndices.put(sorting, List.of());
        }
    }

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
        return getSortedPublicWarps(PwarpSorting.MOST_VISITED, null);
    }

    @Override
    public List<Pwarp> getSortedPublicWarps(PwarpSorting sorting, String categoryFilter) {
        PwarpSorting targetSorting = (sorting != null) ? sorting : PwarpSorting.MOST_VISITED;
        List<Pwarp> baseList = this.sortedPublicIndices.getOrDefault(targetSorting, List.of());

        if (categoryFilter == null || categoryFilter.isBlank() || categoryFilter.equalsIgnoreCase("all")) {
            return baseList;
        }

        String filter = categoryFilter.toLowerCase(Locale.ROOT);
        List<Pwarp> filtered = new ArrayList<>();
        for (Pwarp pwarp : baseList) {
            if (pwarp.category().equalsIgnoreCase(filter)) {
                filtered.add(pwarp);
            }
        }
        return List.copyOf(filtered);
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
        for (PwarpSorting sorting : PwarpSorting.values()) {
            this.sortedPublicIndices.put(sorting, List.of());
        }
        this.sortedAllWarps = List.of();
    }

    private synchronized void rebuildSortedIndices() {
        List<Pwarp> allList = new ArrayList<>(this.warpMap.values());
        allList.sort(Comparator.comparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        this.sortedAllWarps = List.copyOf(allList);

        List<Pwarp> publicBase = new ArrayList<>();
        for (Pwarp pwarp : allList) {
            if (!pwarp.isPrivate()) {
                publicBase.add(pwarp);
            }
        }

        // 1. MOST_VISITED
        List<Pwarp> mostVisited = new ArrayList<>(publicBase);
        mostVisited.sort(Comparator.comparingLong(Pwarp::visits).reversed()
            .thenComparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        this.sortedPublicIndices.put(PwarpSorting.MOST_VISITED, List.copyOf(mostVisited));

        // 2. NEWEST
        List<Pwarp> newest = new ArrayList<>(publicBase);
        newest.sort(Comparator.comparingLong(Pwarp::createdAt).reversed()
            .thenComparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        this.sortedPublicIndices.put(PwarpSorting.NEWEST, List.copyOf(newest));

        // 3. OLDEST
        List<Pwarp> oldest = new ArrayList<>(publicBase);
        oldest.sort(Comparator.comparingLong(Pwarp::createdAt)
            .thenComparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        this.sortedPublicIndices.put(PwarpSorting.OLDEST, List.copyOf(oldest));

        // 4. ALPHABETICAL
        List<Pwarp> alphabetical = new ArrayList<>(publicBase);
        alphabetical.sort(Comparator.comparing(Pwarp::name, String.CASE_INSENSITIVE_ORDER));
        this.sortedPublicIndices.put(PwarpSorting.ALPHABETICAL, List.copyOf(alphabetical));
    }
}
