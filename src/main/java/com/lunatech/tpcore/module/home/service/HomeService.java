package com.lunatech.tpcore.module.home.service;

import com.lunatech.tpcore.module.home.model.Home;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public interface HomeService {
    CompletableFuture<HomeResultStatus> setHome(Player player, String name, boolean force);
    CompletableFuture<HomeResultStatus> deleteHome(Player player, String name);
    CompletableFuture<HomeResultStatus> restoreHome(Player player, String name);
    CompletableFuture<HomeResultStatus> teleport(Player player, UUID owner, String name, String permission, boolean respawn);
    CompletableFuture<List<Home>> list(UUID owner, int limit, long offset);
    long remainingCooldown(UUID player);
}
