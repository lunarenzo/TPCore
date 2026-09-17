package com.lunatech.tpcore.module.home.repository;

import com.lunatech.tpcore.module.home.model.Home;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface HomeRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Map<String, Home>> loadAll(UUID ownerUuid);

    CompletableFuture<Optional<Home>> findByName(UUID ownerUuid, String homeName);

    CompletableFuture<Void> save(Home home);

    CompletableFuture<Void> delete(UUID ownerUuid, String homeName);

    CompletableFuture<Void> deleteAll(UUID ownerUuid);

    CompletableFuture<Void> close();
}
