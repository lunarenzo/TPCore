package com.lunatech.tpcore.module.pwarp.repository;

import com.lunatech.tpcore.module.pwarp.model.PwarpRating;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PwarpRatingRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Void> saveRating(int warpId, UUID playerUuid, int stars);

    CompletableFuture<Optional<PwarpRating>> getRating(int warpId, UUID playerUuid);

    CompletableFuture<List<PwarpRating>> getRatingsForWarp(int warpId);

    CompletableFuture<List<PwarpRating>> loadAllRatings();

    CompletableFuture<Void> close();
}
