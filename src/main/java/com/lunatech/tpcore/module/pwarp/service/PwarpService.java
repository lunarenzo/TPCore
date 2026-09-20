package com.lunatech.tpcore.module.pwarp.service;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.model.PwarpSorting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public interface PwarpService {

    CompletableFuture<Void> initialize();

    CompletableFuture<Boolean> setWarp(Player player, String name, String description);

    CompletableFuture<Boolean> setWarp(Player player, String name, String category, String description);

    CompletableFuture<Boolean> deleteWarp(Player player, String name);

    CompletableFuture<Boolean> executeTeleport(Player player, String name);

    CompletableFuture<Boolean> executeTeleport(Player player, Pwarp pwarp);

    CompletableFuture<Boolean> rateWarp(Player player, String name, int stars);

    Optional<Pwarp> getWarp(String name);

    List<Pwarp> getPublicWarps();

    List<Pwarp> getPublicWarps(PwarpSorting sorting, String categoryFilter);

    List<Pwarp> getPlayerWarps(UUID ownerUuid);

    int getMaxWarpLimit(Player player);

    long getRemainingCooldownSeconds(UUID playerUniqueId);

    boolean isWarmingUp(UUID playerUniqueId);

    boolean hasActiveWarmups();

    void cancelWarmup(UUID playerUniqueId);

    void shutdown();

    void updateConfig(com.lunatech.tpcore.module.pwarp.config.PwarpConfig newConfig);
}
