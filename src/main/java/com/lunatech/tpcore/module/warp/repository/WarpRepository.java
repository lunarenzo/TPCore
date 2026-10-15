package com.lunatech.tpcore.module.warp.repository;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import java.util.Collection;
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
    CompletableFuture<Void> replace(String oldName, Warp updated);

    CompletableFuture<Void> saveAll(Collection<Warp> warps);
    /** Merge active warps and recovery history in one atomic persistence operation. */
    CompletableFuture<Void> mergeSnapshot(Collection<Warp> warps, Collection<DeletedWarp> history);

    CompletableFuture<Void> delete(String warpName);
    CompletableFuture<Void> archiveAndDelete(DeletedWarp entry);
    CompletableFuture<List<DeletedWarp>> loadDeleted();
    CompletableFuture<Void> restore(DeletedWarp entry);
    CompletableFuture<Void> saveDeleted(Collection<DeletedWarp> entries);

    CompletableFuture<Void> close();
}
