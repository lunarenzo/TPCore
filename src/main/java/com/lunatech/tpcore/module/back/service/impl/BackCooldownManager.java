package com.lunatech.tpcore.module.back.service.impl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackCooldownManager {

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public boolean isOnCooldown(UUID uuid, int cooldownSeconds) {
        if (uuid == null || cooldownSeconds <= 0) {
            return false;
        }
        Long lastTime = cooldowns.get(uuid);
        if (lastTime == null) {
            return false;
        }
        long passedMs = System.currentTimeMillis() - lastTime;
        if (passedMs >= (cooldownSeconds * 1000L)) {
            cooldowns.remove(uuid, lastTime);
            return false;
        }
        return true;
    }

    public long getRemainingCooldownSeconds(UUID uuid, int cooldownSeconds) {
        if (uuid == null || cooldownSeconds <= 0) {
            return 0;
        }
        Long lastTime = cooldowns.get(uuid);
        if (lastTime == null) {
            return 0;
        }
        long passedMs = System.currentTimeMillis() - lastTime;
        long cooldownMs = cooldownSeconds * 1000L;
        if (passedMs >= cooldownMs) {
            cooldowns.remove(uuid, lastTime);
            return 0;
        }
        return Math.max(1, (cooldownMs - passedMs + 999) / 1000);
    }

    public void applyCooldown(UUID uuid) {
        if (uuid != null) {
            cooldowns.put(uuid, System.currentTimeMillis());
        }
    }

    public void removeCooldown(UUID uuid) {
        if (uuid != null) {
            cooldowns.remove(uuid);
        }
    }

    public void clear() {
        cooldowns.clear();
    }
}
