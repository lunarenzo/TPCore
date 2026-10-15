package com.lunatech.tpcore.module.warp.hook.impl;

import com.lunatech.tpcore.module.warp.hook.WarpProtectionHook;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Public WorldGuard 7 API only; members are resolved once, never in gameplay checks. */
public final class WorldGuardWarpHook implements WarpProtectionHook {
    private final Plugin provider;
    private final Method wrap, adapt, getInstance, platform, container, query, test;
    private final Object entry, exit, build;

    public WorldGuardWarpHook(Plugin provider) throws ReflectiveOperationException {
        this.provider = provider;
        ClassLoader loader = provider.getClass().getClassLoader();
        Class<?> wg = loader.loadClass("com.sk89q.worldguard.WorldGuard");
        Class<?> wgPlatform = loader.loadClass("com.sk89q.worldguard.internal.platform.WorldGuardPlatform");
        Class<?> regionContainer = loader.loadClass("com.sk89q.worldguard.protection.regions.RegionContainer");
        Class<?> regionQuery = loader.loadClass("com.sk89q.worldguard.protection.regions.RegionQuery");
        Class<?> localPlayer = loader.loadClass("com.sk89q.worldguard.LocalPlayer");
        Class<?> location = loader.loadClass("com.sk89q.worldedit.util.Location");
        Class<?> flag = loader.loadClass("com.sk89q.worldguard.protection.flags.StateFlag");
        Class<?> flags = loader.loadClass("com.sk89q.worldguard.protection.flags.Flags");
        wrap = provider.getClass().getMethod("wrapPlayer", Player.class);
        adapt = loader.loadClass("com.sk89q.worldedit.bukkit.BukkitAdapter").getMethod("adapt", Location.class);
        getInstance = wg.getMethod("getInstance");
        platform = wg.getMethod("getPlatform");
        container = wgPlatform.getMethod("getRegionContainer");
        query = regionContainer.getMethod("createQuery");
        Class<?> array = Array.newInstance(flag, 0).getClass();
        test = regionQuery.getMethod("testState", location, localPlayer, array);
        entry = single(flag, flags.getField("ENTRY").get(null));
        exit = single(flag, flags.getField("EXIT").get(null));
        build = single(flag, flags.getField("BUILD").get(null));
    }

    private static Object single(Class<?> type, Object flag) {
        Object array = Array.newInstance(type, 1);
        Array.set(array, 0, flag);
        return array;
    }

    private boolean check(Player player, Location location, Object flags) throws ReflectiveOperationException {
        if (!provider.isEnabled()) return false;
        if (player.hasPermission("worldguard.region.bypass." + location.getWorld().getName())) return true;
        Object currentQuery = query.invoke(container.invoke(platform.invoke(getInstance.invoke(null))));
        return Boolean.TRUE.equals(test.invoke(currentQuery, adapt.invoke(null, location), wrap.invoke(provider, player), flags));
    }

    @Override public boolean allowsCreation(Player player, Location location) throws ReflectiveOperationException { return check(player, location, build); }
    @Override public boolean allowsTravel(Player player, Location source, Location destination) throws ReflectiveOperationException {
        return check(player, source, exit) && check(player, destination, entry);
    }
}
