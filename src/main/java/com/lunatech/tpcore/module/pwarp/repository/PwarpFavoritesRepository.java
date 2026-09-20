package com.lunatech.tpcore.module.pwarp.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PwarpFavoritesRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Void> addFavorite(UUID playerUuid, int warpId);

    CompletableFuture<Void> removeFavorite(UUID playerUuid, int warpId);

    CompletableFuture<Set<Integer>> getFavorites(UUID playerUuid);

    CompletableFuture<Map<UUID, Set<Integer>>> loadAllFavorites();

    CompletableFuture<Void> close();
}
