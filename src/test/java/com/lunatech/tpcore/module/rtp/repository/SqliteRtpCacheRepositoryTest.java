package com.lunatech.tpcore.module.rtp.repository;

import com.lunatech.tpcore.module.rtp.repository.impl.SqliteRtpCacheRepository;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SqliteRtpCacheRepositoryTest {

    @Test
    @DisplayName("Verify SQLite repository table creation, saving, loading, and deletion")
    void testRepositoryLifecycle(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("test_rtp_cache.db").toFile();
        SqliteRtpCacheRepository repo = new SqliteRtpCacheRepository(dbFile, LoggerFactory.getLogger("TestLogger"));

        // Initialize DB schema
        repo.initialize().join();

        UUID worldUuid = UUID.randomUUID();
        long loc1 = PackedLocation.pack(500, 70, -1000);
        long loc2 = PackedLocation.pack(-250, 68, 1200);

        // Save locations
        repo.savePackedLocation(worldUuid, loc1).join();
        repo.savePackedLocation(worldUuid, loc2).join();

        // Load locations
        List<Long> loaded = repo.loadPackedLocations(worldUuid).join();
        Assertions.assertEquals(2, loaded.size());
        Assertions.assertTrue(loaded.contains(loc1));
        Assertions.assertTrue(loaded.contains(loc2));

        // Delete single location
        repo.deletePackedLocation(worldUuid, loc1).join();
        List<Long> afterDelete = repo.loadPackedLocations(worldUuid).join();
        Assertions.assertEquals(1, afterDelete.size());
        Assertions.assertEquals(loc2, afterDelete.get(0));

        // Clear all
        repo.clearAll(worldUuid).join();
        List<Long> afterClear = repo.loadPackedLocations(worldUuid).join();
        Assertions.assertTrue(afterClear.isEmpty());

        // Close repository
        repo.close().join();
    }
}
