package com.lunatech.tpcore.module.spawn.service.impl;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.util.UUID;

public final class SpawnCooldownManager {

    private final Object2LongOpenHashMap<UUID> cooldowns = new Object2LongOpenHashMap<>();
    private final Object lock = new Object();

    public boolean isOnCooldown(UUID playerId, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return false;
        }
        return this.getRemainingCooldownMs(playerId, cooldownSeconds) > 0;
    }

    public long getRemainingCooldownMs(UUID playerId, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return 0L;
        }
        long lastTime;
        synchronized (this.lock) {
            lastTime = this.cooldowns.getOrDefault(playerId, 0L);
        }
        if (lastTime <= 0) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - lastTime;
        long cooldownMs = cooldownSeconds * 1000L;
        return Math.max(0L, cooldownMs - elapsed);
    }

    public void applyCooldown(UUID playerId) {
        synchronized (this.lock) {
            this.cooldowns.put(playerId, System.currentTimeMillis());
        }
    }

    public void removeCooldown(UUID playerId) {
        synchronized (this.lock) {
            this.cooldowns.removeLong(playerId);
        }
    }

    public void clear() {
        synchronized (this.lock) {
            this.cooldowns.clear();
        }
    }
}
