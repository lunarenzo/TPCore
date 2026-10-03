package com.lunatech.tpcore.module.back.service.impl;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.cache.BackCache;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.BackRepository;
import com.lunatech.tpcore.module.back.repository.impl.SqliteBackRepository;
import com.lunatech.tpcore.module.back.repository.impl.YamlBackRepository;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;

public final class BackDataMigrator {

    private final File dataFolder;
    private final Logger logger;
    private final Supplier<BackConfig> configSupplier;
    private final BackCache cache;
    private final BackRepository activeRepository;

    public BackDataMigrator(File dataFolder, Logger logger, Supplier<BackConfig> configSupplier, BackCache cache, BackRepository activeRepository) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.activeRepository = Objects.requireNonNull(activeRepository, "activeRepository cannot be null");
    }

    public CompletableFuture<Integer> migrateData(String fromStorage, String toStorage) {
        if (fromStorage == null || toStorage == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Storage engine types cannot be null"));
        }
        String from = fromStorage.trim().toUpperCase();
        String to = toStorage.trim().toUpperCase();

        if (!isValidStorageType(from) || !isValidStorageType(to)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("INVALID_STORAGE_TYPE"));
        }
        if (from.equals(to)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("SAME_STORAGE_TYPE"));
        }

        return CompletableFuture.supplyAsync(() -> {
            BackConfig config = configSupplier.get();
            String activeType = (config.storage() != null && "YAML".equalsIgnoreCase(config.storage().type())) ? "YAML" : "SQLITE";
            boolean isFromActive = from.equals(activeType);
            boolean isToActive = to.equals(activeType);

            BackRepository fromRepo = isFromActive ? activeRepository : createRepoForType(from);
            BackRepository toRepo = isToActive ? activeRepository : createRepoForType(to);

            try {
                if (!isFromActive) fromRepo.initialize().join();
                if (!isToActive) toRepo.initialize().join();

                Map<UUID, List<BackLocation>> allData = fromRepo.loadAll().join();
                int count = 0;
                for (Map.Entry<UUID, List<BackLocation>> entry : allData.entrySet()) {
                    UUID playerUuid = entry.getKey();
                    List<BackLocation> history = entry.getValue();
                    toRepo.savePlayerHistory(playerUuid, history).join();
                    if (isToActive) {
                        for (int i = history.size() - 1; i >= 0; i--) {
                            cache.pushLocation(playerUuid, history.get(i), config.maxHistoryDepth());
                        }
                    }
                    count += history.size();
                }
                logger.info("Successfully migrated {} back entries from {} storage to {} storage.", count, from, to);
                return count;
            } finally {
                if (!isFromActive && fromRepo != null) fromRepo.close().join();
                if (!isToActive && toRepo != null) toRepo.close().join();
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    private boolean isValidStorageType(String type) {
        return "SQLITE".equalsIgnoreCase(type) || "YAML".equalsIgnoreCase(type);
    }

    private BackRepository createRepoForType(String type) {
        return "YAML".equalsIgnoreCase(type)
            ? new YamlBackRepository(dataFolder, logger)
            : new SqliteBackRepository(dataFolder, logger);
    }
}
