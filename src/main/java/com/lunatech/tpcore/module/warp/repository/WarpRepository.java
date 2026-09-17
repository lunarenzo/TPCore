package com.lunatech.tpcore.module.warp.repository;

import com.lunatech.tpcore.module.warp.model.Warp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface WarpRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Map<String, Warp>> loadAll();

    CompletableFuture<Optional<Warp>> findByName(String warpName);

    CompletableFuture<List<Warp>> findByCategory(String category);

    CompletableFuture<Void> save(Warp warp);

    CompletableFuture<Void> delete(String warpName);

    CompletableFuture<Void> close();
}
