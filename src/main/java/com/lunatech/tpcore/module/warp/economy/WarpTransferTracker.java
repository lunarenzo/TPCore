package com.lunatech.tpcore.module.warp.economy;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Module-owned liveness across economy service restarts; retains identities only, never providers or futures. */
public final class WarpTransferTracker {
    private final Set<UUID> live = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity;
    public WarpTransferTracker(int capacity) { this.capacity = new Semaphore(Math.max(1, capacity)); }
    public boolean add(UUID ticket) {
        if (!capacity.tryAcquire()) return false;
        if (!live.add(ticket)) { capacity.release(); return false; }
        return true;
    }
    public boolean contains(UUID ticket) { return live.contains(ticket); }
    public boolean isEmpty() { return live.isEmpty(); }
    public void remove(UUID ticket) { if (live.remove(ticket)) capacity.release(); }
}
