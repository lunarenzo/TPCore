package com.lunatech.tpcore.module.pwarp.repository;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PwarpRepository {

    CompletableFuture<Void> initialize();

    CompletableFuture<Void> savePwarp(Pwarp pwarp);

    CompletableFuture<Void> deletePwarp(String name);

    CompletableFuture<Optional<Pwarp>> findByName(String name);

    CompletableFuture<List<Pwarp>> findByOwner(UUID ownerUuid);

    CompletableFuture<List<Pwarp>> loadAllPwarps();

    CompletableFuture<Void> incrementVisits(String name);

    CompletableFuture<Void> updatePrice(String name, double price);

    CompletableFuture<Void> updateBank(String name, double bank);

    CompletableFuture<Void> close();
}
