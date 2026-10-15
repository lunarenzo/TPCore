package com.lunatech.tpcore.module.warp.hook;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface WarpProtectionHook {
    boolean allowsCreation(Player player, Location location) throws ReflectiveOperationException;
    boolean allowsTravel(Player player, Location source, Location destination) throws ReflectiveOperationException;
}
