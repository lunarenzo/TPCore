package com.lunatech.tpcore.module.back.repository;

import com.lunatech.tpcore.module.back.model.BackLocation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface BackRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Map<UUID, List<BackLocation>>> loadAll();

    CompletableFuture<List<BackLocation>> loadPlayerHistory(UUID playerUuid);

    CompletableFuture<Void> savePlayerHistory(UUID playerUuid, List<BackLocation> history);

    CompletableFuture<Void> deletePlayerHistory(UUID playerUuid);

    CompletableFuture<Void> close();
}
