package com.lunatech.tpcore.module.warp.cache.impl;

import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.model.Warp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultWarpCache implements WarpCache {

    private final Map<String, Warp> warpMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> categoryIndex = new ConcurrentHashMap<>();

    @Override
    public void populate(Map<String, Warp> warps) {
        clear();
        if (warps != null) {
            for (Warp warp : warps.values()) {
                putWarp(warp);
            }
        }
    }

    @Override
    public Optional<Warp> getWarp(String warpName) {
        if (warpName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(warpMap.get(warpName.toLowerCase()));
    }

    @Override
    public void putWarp(Warp warp) {
        Objects.requireNonNull(warp, "warp cannot be null");
        String key = warp.name().toLowerCase();
        Warp existing = warpMap.put(key, warp);

        // If replacing existing warp whose category changed, clean up old index entry
        if (existing != null && !existing.category().equalsIgnoreCase(warp.category())) {
            String oldCatKey = existing.category().toLowerCase();
            categoryIndex.computeIfPresent(oldCatKey, (k, set) -> {
                set.remove(key);
                return set.isEmpty() ? null : set;
            });
        }

        // Add to new category index
        String catKey = warp.category().toLowerCase();
        categoryIndex.computeIfAbsent(catKey, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    @Override
    public void removeWarp(String warpName) {
        if (warpName == null) {
            return;
        }
        String key = warpName.toLowerCase();
        Warp removed = warpMap.remove(key);
        if (removed != null) {
            String catKey = removed.category().toLowerCase();
            categoryIndex.computeIfPresent(catKey, (k, set) -> {
                set.remove(key);
                return set.isEmpty() ? null : set;
            });
        }
    }

    @Override
    public Collection<Warp> getAllWarps() {
        return Collections.unmodifiableCollection(new ArrayList<>(warpMap.values()));
    }

    @Override
    public List<Warp> getWarpsByCategory(String category) {
        if (category == null) {
            return Collections.emptyList();
        }
        Set<String> keys = categoryIndex.get(category.toLowerCase());
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<Warp> list = new ArrayList<>();
        for (String k : keys) {
            Warp warp = warpMap.get(k);
            if (warp != null) {
                list.add(warp);
            }
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public Set<String> getCategories() {
        return Collections.unmodifiableSet(categoryIndex.keySet());
    }

    @Override
    public int getWarpCount() {
        return warpMap.size();
    }

    @Override
    public void clear() {
        warpMap.clear();
        categoryIndex.clear();
    }
}
