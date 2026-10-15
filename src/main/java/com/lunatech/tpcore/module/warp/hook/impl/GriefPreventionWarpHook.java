package com.lunatech.tpcore.module.warp.hook.impl;

import com.lunatech.tpcore.module.warp.hook.WarpProtectionHook;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

/** Requires the modern claim permission API; deliberately does not guess a deprecated fallback. */
public final class GriefPreventionWarpHook implements WarpProtectionHook {
    private final Plugin provider;
    private final Field dataStore;
    private final Method claimAt, permission;
    private final Object access, build;

    public GriefPreventionWarpHook(Plugin provider) throws ReflectiveOperationException {
        this.provider = provider;
        ClassLoader loader = provider.getClass().getClassLoader();
        Class<?> claim = loader.loadClass("me.ryanhamshire.GriefPrevention.Claim");
        Class<?> permissions = loader.loadClass("me.ryanhamshire.GriefPrevention.ClaimPermission");
        dataStore = provider.getClass().getField("dataStore");
        claimAt = dataStore.getType().getMethod("getClaimAt", Location.class, boolean.class, claim);
        permission = claim.getMethod("checkPermission", Player.class, permissions, Event.class);
        access = permissions.getField("Access").get(null);
        build = permissions.getField("Build").get(null);
    }

    private boolean check(Player player, Location location, Object required) throws ReflectiveOperationException {
        if (!provider.isEnabled()) return false;
        Object store = dataStore.get(provider);
        if (store == null) return false;
        Object claim = claimAt.invoke(store, location, false, null);
        // A non-null denial supplier means denied; do not render it or perform message lookups.
        return claim == null || permission.invoke(claim, player, required, null) == null;
    }

    @Override public boolean allowsCreation(Player player, Location location) throws ReflectiveOperationException { return check(player, location, build); }
    @Override public boolean allowsTravel(Player player, Location source, Location destination) throws ReflectiveOperationException {
        // GP access trust is not an exit restriction. Source escape rules belong to world rules.
        return check(player, destination, access);
    }
}
