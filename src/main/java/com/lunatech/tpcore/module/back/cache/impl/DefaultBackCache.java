package com.lunatech.tpcore.module.back.cache.impl;

import com.lunatech.tpcore.module.back.cache.BackCache;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class DefaultBackCache implements BackCache {

    private final Map<UUID, ConcurrentLinkedDeque<BackLocation>> cache = new ConcurrentHashMap<>();

    @Override
    public void populate(Map<UUID, List<BackLocation>> data) {
        clear();
        if (data != null) {
            for (Map.Entry<UUID, List<BackLocation>> entry : data.entrySet()) {
                UUID uuid = entry.getKey();
                List<BackLocation> list = entry.getValue();
                if (uuid != null && list != null && !list.isEmpty()) {
                    ConcurrentLinkedDeque<BackLocation> deque = new ConcurrentLinkedDeque<>(list);
                    cache.put(uuid, deque);
                }
            }
        }
    }

    @Override
    public void pushLocation(UUID playerUuid, BackLocation location, int maxHistoryDepth) {
        Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        Objects.requireNonNull(location, "location cannot be null");

        int maxDepth = Math.max(1, maxHistoryDepth);
        ConcurrentLinkedDeque<BackLocation> deque = cache.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(location);

        while (deque.size() > maxDepth) {
            deque.removeLast();
        }
    }

    @Override
    public Optional<BackLocation> peekLastLocation(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        ConcurrentLinkedDeque<BackLocation> deque = cache.get(playerUuid);
        if (deque == null || deque.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(deque.peekFirst());
    }

    @Override
    public Optional<BackLocation> popLastLocation(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        ConcurrentLinkedDeque<BackLocation> deque = cache.get(playerUuid);
        if (deque == null || deque.isEmpty()) {
            return Optional.empty();
        }
        BackLocation loc = deque.pollFirst();
        if (deque.isEmpty()) {
            cache.remove(playerUuid, deque);
        }
        return Optional.ofNullable(loc);
    }

    @Override
    public Optional<BackLocation> peekLastDeathLocation(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        ConcurrentLinkedDeque<BackLocation> deque = cache.get(playerUuid);
        if (deque == null || deque.isEmpty()) {
            return Optional.empty();
        }
        for (BackLocation loc : deque) {
            if (loc.cause() == BackCause.DEATH) {
                return Optional.of(loc);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<BackLocation> popLastDeathLocation(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        ConcurrentLinkedDeque<BackLocation> deque = cache.get(playerUuid);
        if (deque == null || deque.isEmpty()) {
            return Optional.empty();
        }
        for (BackLocation loc : deque) {
            if (loc.cause() == BackCause.DEATH) {
                deque.remove(loc);
                if (deque.isEmpty()) {
                    cache.remove(playerUuid, deque);
                }
                return Optional.of(loc);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<BackLocation> getHistory(UUID playerUuid) {
        if (playerUuid == null) {
            return Collections.emptyList();
        }
        ConcurrentLinkedDeque<BackLocation> deque = cache.get(playerUuid);
        if (deque == null || deque.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(deque));
    }

    @Override
    public void clearPlayerHistory(UUID playerUuid) {
        if (playerUuid != null) {
            cache.remove(playerUuid);
        }
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
