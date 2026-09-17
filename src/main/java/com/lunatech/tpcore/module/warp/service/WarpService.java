package com.lunatech.tpcore.module.warp.service;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.model.Warp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public interface WarpService {

    CompletableFuture<Void> initialize();

    CompletableFuture<WarpResultStatus> teleportToWarp(Player player, String warpName, String rawPassword);

    CompletableFuture<WarpResultStatus> teleportOtherToWarp(Player sender, Player target, String warpName);

    CompletableFuture<WarpResultStatus> setWarp(Player creator, String warpName, boolean overwrite, String rawPassword, String category);

    CompletableFuture<WarpResultStatus> deleteWarp(String warpName);

    Optional<Warp> getWarp(String warpName);

    Collection<Warp> getAllWarps();

    List<Warp> getWarpsByCategory(String category);

    Set<String> getCategories();

    void cancelWarmupOnMove(Player player);

    void cancelWarmupOnDamage(Player player);

    void cancelWarmupOnQuit(UUID playerUuid);

    long getRemainingCooldownSeconds(UUID playerUuid);

    void updateConfig(WarpConfig newConfig);

    CompletableFuture<Integer> migrateData(String fromStorage, String toStorage);

    CompletableFuture<Void> close();
}
