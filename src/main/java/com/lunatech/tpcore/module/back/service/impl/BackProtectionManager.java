package com.lunatech.tpcore.module.back.service.impl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackProtectionManager {

    private final Map<UUID, Long> protectedPlayers = new ConcurrentHashMap<>();

    public void grantProtection(UUID uuid, int seconds) {
        if (uuid == null || seconds <= 0) return;
        long expiry = System.currentTimeMillis() + (seconds * 1000L);
        protectedPlayers.put(uuid, expiry);
    }

    public boolean isProtected(UUID uuid) {
        if (uuid == null) return false;
        Long expiry = protectedPlayers.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            protectedPlayers.remove(uuid, expiry);
            return false;
        }
        return true;
    }

    public void removeProtection(UUID uuid) {
        if (uuid != null) {
            protectedPlayers.remove(uuid);
        }
    }

    public void clear() {
        protectedPlayers.clear();
    }
}
