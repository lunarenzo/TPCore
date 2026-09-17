package com.lunatech.tpcore.module.warp.cache;

import com.lunatech.tpcore.module.warp.model.Warp;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface WarpCache {

    void populate(Map<String, Warp> warps);

    Optional<Warp> getWarp(String warpName);

    void putWarp(Warp warp);

    void removeWarp(String warpName);

    Collection<Warp> getAllWarps();

    List<Warp> getWarpsByCategory(String category);

    Set<String> getCategories();

    int getWarpCount();

    void clear();
}
