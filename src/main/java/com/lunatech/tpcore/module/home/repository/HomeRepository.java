package com.lunatech.tpcore.module.home.repository;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.model.HomePage;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface HomeRepository {
    CompletableFuture<Void> initialize();
    CompletableFuture<Optional<Home>> findByName(UUID owner, String name);
    CompletableFuture<Optional<Home>> findMatching(HomePage.Entry expected);
    CompletableFuture<HomePage> page(UUID owner, long page);
    CompletableFuture<List<Home>> list(UUID owner, int limit, long offset);
    CompletableFuture<List<String>> suggestNames(UUID owner, String prefix, int limit);
    CompletableFuture<HomeResultStatus> save(Home home, boolean overwrite, int limit);
    CompletableFuture<HomeResultStatus> archive(UUID owner, String name, int capacity, long retentionMillis);
    CompletableFuture<HomeResultStatus> archiveMatching(HomePage.Entry expected, int capacity, long retentionMillis);
    CompletableFuture<HomeResultStatus> restore(UUID owner, String name, int limit, long retentionMillis);
    CompletableFuture<Void> close();
}
