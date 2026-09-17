package com.lunatech.tpcore.config;

import java.util.concurrent.CompletableFuture;

public interface ReloadableModule {

    String getModuleName();

    boolean reloadConfig();

    default boolean supportsMigration() {
        return false;
    }

    default CompletableFuture<Integer> migrateData(String fromStorage, String toStorage) {
        return CompletableFuture.completedFuture(-1);
    }
}
