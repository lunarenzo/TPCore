package com.lunatech.tpcore.module.pwarp.repository.impl;

import com.lunatech.tpcore.module.pwarp.model.PwarpRating;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRatingRepository;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SqlitePwarpRatingRepositoryTest {

    private PwarpRatingRepository repository;
    private File dbFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.dbFile = tempDir.resolve("test_pwarp_ratings.db").toFile();
        this.repository = new SqlitePwarpRatingRepository(dbFile, LoggerFactory.getLogger("TestRatingLogger"));
        this.repository.initialize().join();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close().join();
        }
    }

    @Test
    @DisplayName("Verify rating save, upsert update, and single player lookup")
    void testSaveAndGetRating() {
        int warpId = 1;
        UUID playerUuid = UUID.randomUUID();

        // Initial rating
        repository.saveRating(warpId, playerUuid, 4).join();

        Optional<PwarpRating> ratingOpt = repository.getRating(warpId, playerUuid).join();
        Assertions.assertTrue(ratingOpt.isPresent());
        Assertions.assertEquals(4, ratingOpt.get().stars());

        // Upsert rate change (4 -> 5 stars)
        repository.saveRating(warpId, playerUuid, 5).join();

        Optional<PwarpRating> updatedOpt = repository.getRating(warpId, playerUuid).join();
        Assertions.assertTrue(updatedOpt.isPresent());
        Assertions.assertEquals(5, updatedOpt.get().stars());
    }

    @Test
    @DisplayName("Verify loading all ratings for a warp")
    void testGetRatingsForWarp() {
        int warpId = 10;
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        repository.saveRating(warpId, p1, 5).join();
        repository.saveRating(warpId, p2, 3).join();

        List<PwarpRating> ratings = repository.getRatingsForWarp(warpId).join();
        Assertions.assertEquals(2, ratings.size());
    }
}
