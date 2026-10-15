package com.lunatech.tpcore.module.warp.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Module-owned unresolved dispatches survive a module reload. No live server objects are retained. */
public final class WarpTeleportTracker {
    public record Evidence(UUID ticket, UUID player, String warp, UUID world, double x, double y, double z,
        UUID paymentTicket, long dispatchedAt) {}
    private final Map<UUID, Evidence> pending = new ConcurrentHashMap<>();
    private final Semaphore capacity;
    public WarpTeleportTracker(int capacity) { this.capacity = new Semaphore(Math.max(1, capacity)); }
    public boolean contains(UUID player) { return pending.containsKey(player); }
    public int size() { return pending.size(); }
    public boolean reserve(Evidence evidence) {
        if (!capacity.tryAcquire()) return false;
        if (pending.putIfAbsent(evidence.player(), evidence) != null) { capacity.release(); return false; }
        return true;
    }
    public void complete(Evidence evidence) { if (pending.remove(evidence.player(), evidence)) capacity.release(); }
}
