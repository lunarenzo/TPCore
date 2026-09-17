package com.lunatech.tpcore.module.back.service;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface BackService {

    CompletableFuture<Void> initialize();

    CompletableFuture<BackResultStatus> teleportBack(Player player);

    CompletableFuture<BackResultStatus> teleportDeath(Player player);

    CompletableFuture<BackResultStatus> teleportToHistory(Player player, int index);

    void recordLocation(Player player, Location location, BackCause cause);

    void recordDeathLocation(Player player, Location location);

    Optional<BackLocation> getLastLocation(Player player);

    List<BackLocation> getHistory(Player player);

    void clearHistory(Player player);

    void cancelWarmupOnMove(Player player);

    void cancelWarmupOnDamage(Player player);

    void cancelWarmupOnQuit(UUID playerUuid);

    long getRemainingCooldownSeconds(UUID playerUuid);

    void updateConfig(BackConfig newConfig);

    CompletableFuture<Integer> migrateData(String fromStorage, String toStorage);

    CompletableFuture<Void> close();
}
