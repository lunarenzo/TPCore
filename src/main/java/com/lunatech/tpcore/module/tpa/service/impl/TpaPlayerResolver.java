package com.lunatech.tpcore.module.tpa.service.impl;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Non-blocking offline player resolution helper across Paper / Spigot versions.
 */
final class TpaPlayerResolver {

    private static final Method GET_OFFLINE_PLAYER_IF_CACHED;
    private static final Method GET_OFFLINE_PLAYER_IF_CACHED_UUID;

    static {
        Method mName = null;
        Method mUuid = null;
        try {
            mName = Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class);
            mName.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            mUuid = Bukkit.class.getMethod("getOfflinePlayerIfCached", UUID.class);
            mUuid.setAccessible(true);
        } catch (Throwable ignored) {
        }
        GET_OFFLINE_PLAYER_IF_CACHED = mName;
        GET_OFFLINE_PLAYER_IF_CACHED_UUID = mUuid;
    }

    private TpaPlayerResolver() {
    }

    static OfflinePlayer resolveOfflinePlayerIfCached(String name) {
        if (GET_OFFLINE_PLAYER_IF_CACHED != null && name != null) {
            try {
                return (OfflinePlayer) GET_OFFLINE_PLAYER_IF_CACHED.invoke(null, name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static OfflinePlayer resolveOfflinePlayerIfCached(UUID uuid) {
        if (GET_OFFLINE_PLAYER_IF_CACHED_UUID != null && uuid != null) {
            try {
                OfflinePlayer op = (OfflinePlayer) GET_OFFLINE_PLAYER_IF_CACHED_UUID.invoke(null, uuid);
                if (op != null && op.getName() != null) {
                    return op;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static OfflinePlayer resolveOfflinePlayer(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = resolveOfflinePlayerIfCached(uuid);
        if (cached != null) {
            return cached;
        }
        return Bukkit.getOfflinePlayer(uuid);
    }
}
