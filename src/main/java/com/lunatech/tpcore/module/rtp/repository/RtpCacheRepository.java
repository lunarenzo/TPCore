package com.lunatech.tpcore.module.rtp.repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface RtpCacheRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<List<Long>> loadPackedLocations(UUID worldUuid);

    CompletableFuture<Void> savePackedLocation(UUID worldUuid, long packedLocation);

    CompletableFuture<Void> deletePackedLocation(UUID worldUuid, long packedLocation);

    CompletableFuture<Void> clearAll(UUID worldUuid);

    CompletableFuture<Void> close();
}
