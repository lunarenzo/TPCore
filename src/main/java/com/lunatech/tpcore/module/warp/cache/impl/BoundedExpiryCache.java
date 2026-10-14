package com.lunatech.tpcore.module.warp.cache.impl;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** UUID-only expiration state with a strict admission bound. */
public final class BoundedExpiryCache {
    private final ConcurrentHashMap<UUID, Long> entries = new ConcurrentHashMap<>();
    private final Semaphore slots;

    public BoundedExpiryCache(int capacity) { slots = new Semaphore(capacity); }

    public Long get(UUID id) {
        for (;;) {
            Long expiry = entries.get(id);
            if (expiry == null || expiry > System.currentTimeMillis()) return expiry;
            if (entries.remove(id, expiry)) {
                slots.release();
                return null;
            }
        }
    }

    public boolean put(UUID id, long expiry) {
        if (entries.computeIfPresent(id, (key, previous) -> expiry) != null) return true;
        if (!slots.tryAcquire()) {
            long now = System.currentTimeMillis();
            entries.forEach((key, value) -> { if (value <= now && entries.remove(key, value)) slots.release(); });
            if (!slots.tryAcquire()) return false;
        }
        if (entries.putIfAbsent(id, expiry) != null) { slots.release(); entries.computeIfPresent(id, (key, previous) -> expiry); }
        return true;
    }

    public void remove(UUID id) { if (entries.remove(id) != null) slots.release(); }
    public void clear() { entries.keySet().forEach(this::remove); }
}
