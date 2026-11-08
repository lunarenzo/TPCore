package com.lunatech.tpcore.module.home.repository;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface HomeRepository {
    CompletableFuture<Void> initialize();
    CompletableFuture<Optional<Home>> findByName(UUID owner, String name);
    CompletableFuture<List<Home>> list(UUID owner, int limit, long offset);
    CompletableFuture<HomeResultStatus> save(Home home, boolean overwrite, int limit);
    CompletableFuture<HomeResultStatus> archive(UUID owner, String name, int capacity, long retentionMillis);
    CompletableFuture<HomeResultStatus> restore(UUID owner, String name, int limit, long retentionMillis);
    CompletableFuture<Void> close();
}
