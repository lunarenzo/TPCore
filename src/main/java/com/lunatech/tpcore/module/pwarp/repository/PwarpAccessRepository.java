package com.lunatech.tpcore.module.pwarp.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PwarpAccessRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Void> addEntry(int warpId, UUID playerUuid, String listType);

    CompletableFuture<Void> removeEntry(int warpId, UUID playerUuid, String listType);

    CompletableFuture<Set<UUID>> getEntries(int warpId, String listType);

    CompletableFuture<Map<Integer, Set<UUID>>> loadAllEntries(String listType);

    CompletableFuture<Void> close();
}
