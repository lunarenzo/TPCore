package com.lunatech.tpcore.module.pwarp.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PwarpRecentsRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Void> recordVisit(UUID playerUuid, int warpId);

    CompletableFuture<List<Integer>> getRecents(UUID playerUuid);

    CompletableFuture<Map<UUID, List<Integer>>> loadAllRecents();

    CompletableFuture<Void> close();
}
