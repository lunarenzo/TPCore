package com.lunatech.tpcore.module.pwarp;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRepository;
import com.lunatech.tpcore.module.pwarp.repository.impl.SqlitePwarpRepository;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SqlitePwarpRepositoryTest {

    @Test
    @DisplayName("Verify SQLite Pwarp repository initialization, saving, updating, loading, visiting, and deletion")
    void testSqlitePwarpRepositoryLifecycle(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("test_pwarps.db").toFile();
        PwarpRepository repo = new SqlitePwarpRepository(dbFile, LoggerFactory.getLogger("TestLogger"));

        // Initialize schema
        repo.initialize().join();

        UUID owner = UUID.randomUUID();
        Pwarp warp1 = new Pwarp(1, owner, "Steve", "market", "Marketplace warp", "world", 100.5, 64.0, -200.5, 90.0f, 0.0f, "CHEST", "shops", false, System.currentTimeMillis(), 0);
        Pwarp warp2 = new Pwarp(2, owner, "Steve", "homebase", "Base warp", "world", -50.0, 72.0, 300.0, 180.0f, 0.0f, "GRASS_BLOCK", "bases", true, System.currentTimeMillis(), 5);

        // Save warps
        repo.savePwarp(warp1).join();
        repo.savePwarp(warp2).join();

        // Load all
        List<Pwarp> all = repo.loadAllPwarps().join();
        Assertions.assertEquals(2, all.size());

        // Find by name
        Optional<Pwarp> found = repo.findByName("MARKET").join();
        Assertions.assertTrue(found.isPresent());
        Assertions.assertEquals("market", found.get().name());
        Assertions.assertEquals("shops", found.get().category());
        Assertions.assertEquals(100.5, found.get().x());

        // Find by owner
        List<Pwarp> ownerWarps = repo.findByOwner(owner).join();
        Assertions.assertEquals(2, ownerWarps.size());

        // Increment visits
        repo.incrementVisits("market").join();
        Pwarp visited = repo.findByName("market").join().orElseThrow();
        Assertions.assertEquals(1, visited.visits());

        // Delete warp
        repo.deletePwarp("homebase").join();
        List<Pwarp> afterDelete = repo.loadAllPwarps().join();
        Assertions.assertEquals(1, afterDelete.size());
        Assertions.assertEquals("market", afterDelete.get(0).name());

        // Close repository gracefully
        repo.close().join();
    }
}
