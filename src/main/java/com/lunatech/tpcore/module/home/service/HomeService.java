package com.lunatech.tpcore.module.home.service;

import com.lunatech.tpcore.module.home.model.Home;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface HomeService {

    CompletableFuture<HomeResultStatus> setHome(Player player, String homeName, boolean force);

    CompletableFuture<HomeResultStatus> deleteHome(Player player, String homeName);

    CompletableFuture<HomeResultStatus> setHomeOther(Player admin, UUID targetUuid, String homeName, Location location);

    CompletableFuture<HomeResultStatus> deleteHomeOther(Player admin, UUID targetUuid, String homeName);

    CompletableFuture<HomeResultStatus> shareHome(Player player, String homeName, UUID targetUuid);

    CompletableFuture<HomeResultStatus> unshareHome(Player player, String homeName, UUID targetUuid);

    CompletableFuture<Boolean> teleportHome(Player player, String homeName);

    CompletableFuture<Boolean> teleportHomeOther(Player player, UUID targetUuid, String homeName);

    CompletableFuture<Boolean> teleportSharedHome(Player player, UUID ownerUuid, String homeName);

    Optional<Home> getHome(UUID ownerUuid, String homeName);

    Map<String, Home> getHomes(UUID ownerUuid);

    int getMaxHomeLimit(Player player);

    boolean isLocationSafe(Location location);
}
