package com.lunatech.tpcore.module.warp.cache.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.model.Warp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable publication keeps category indexes and name lookup consistent. */
public final class DefaultWarpCache implements WarpCache {
    private record Snapshot(Map<String, Warp> names, Map<String, List<Warp>> categories) {
        static Snapshot of(Map<String, Warp> names) {
            Map<String, List<Warp>> categories = new HashMap<>();
            for (Warp warp : names.values()) {
                categories.computeIfAbsent(warp.category().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(warp);
            }
            categories.replaceAll((key, values) -> List.copyOf(values));
            return new Snapshot(Map.copyOf(names), Map.copyOf(categories));
        }
    }
    private final AtomicReference<Snapshot> state = new AtomicReference<>(Snapshot.of(Map.of()));
    private volatile int capacity = WarpConfig.createDefault().maxCachedWarps();

    @Override public void setCapacity(int capacity) {
        if (capacity < state.get().names().size()) throw new IllegalArgumentException("Cache capacity is below stored warp count");
        this.capacity = Math.max(1, capacity);
    }
    @Override public void populate(Map<String, Warp> warps) {
        Map<String, Warp> normalized = new HashMap<>();
        if (warps != null) warps.values().forEach(warp -> normalized.put(warp.name().toLowerCase(Locale.ROOT), warp));
        if (normalized.size() > capacity) throw new IllegalStateException("Warp storage exceeds configured cache capacity");
        state.set(Snapshot.of(normalized));
    }
    @Override public Optional<Warp> getWarp(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(state.get().names().get(name.toLowerCase(Locale.ROOT)));
    }
    @Override public void putWarp(Warp warp) {
        Objects.requireNonNull(warp);
        state.updateAndGet(old -> {
            Map<String, Warp> names = new HashMap<>(old.names());
            names.put(warp.name().toLowerCase(Locale.ROOT), warp);
            if (names.size() > capacity) throw new IllegalStateException("Warp cache capacity reached");
            return Snapshot.of(names);
        });
    }
    @Override public void removeWarp(String name) {
        if (name == null) return;
        state.updateAndGet(old -> {
            Map<String, Warp> names = new HashMap<>(old.names());
            names.remove(name.toLowerCase(Locale.ROOT));
            return Snapshot.of(names);
        });
    }
    @Override public Collection<Warp> getAllWarps() { return state.get().names().values(); }
    @Override public List<Warp> getWarpsByCategory(String category) {
        return category == null ? List.of() : state.get().categories().getOrDefault(category.toLowerCase(Locale.ROOT), List.of());
    }
    @Override public Set<String> getCategories() { return state.get().categories().keySet(); }
    @Override public int getWarpCount() { return state.get().names().size(); }
    @Override public void clear() { state.set(Snapshot.of(Map.of())); }
}
