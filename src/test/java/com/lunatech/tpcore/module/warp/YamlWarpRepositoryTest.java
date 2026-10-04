package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlWarpRepositoryTest {

    @TempDir
    File tempDir;

    private YamlWarpRepository repository;
    private final UUID worldId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new YamlWarpRepository(tempDir, LoggerFactory.getLogger("Test-YamlWarpRepo"));
        repository.initialize().join();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close().join();
        }
    }

    @Test
    @DisplayName("YamlWarpRepository saves, finds, and deletes case-insensitively")
    void testSaveFindAndDeleteCaseInsensitive() {
        Warp warp = new Warp("Spawn", worldId, "world", 100.5, 64.0, 200.5, 90f, 0f, creatorId, "general", "hash123", true, 1000L);
        repository.save(warp).join();

        Optional<Warp> found = repository.findByName("spawn").join();
        assertTrue(found.isPresent());
        assertEquals("Spawn", found.get().name());

        // Delete with different casing "SPAWN"
        repository.delete("SPAWN").join();

        Optional<Warp> afterDelete = repository.findByName("spawn").join();
        assertFalse(afterDelete.isPresent());
        assertTrue(repository.loadAll().join().isEmpty());
    }

    @Test
    @DisplayName("YamlWarpRepository saveAll batch writes all warps cleanly")
    void testSaveAll() {
        Warp w1 = new Warp("Hub", worldId, "world", 10, 64, 10, 0f, 0f, creatorId, "lobby", null, false, 1000L);
        Warp w2 = new Warp("NetherHub", worldId, "world_nether", 20, 80, 20, 0f, 0f, creatorId, "lobby", null, false, 2000L);

        repository.saveAll(List.of(w1, w2)).join();

        Map<String, Warp> loaded = repository.loadAll().join();
        assertEquals(2, loaded.size());
        assertTrue(loaded.containsKey("hub"));
        assertTrue(loaded.containsKey("netherhub"));
    }

    @Test
    @DisplayName("YamlWarpRepository concurrent saves do not corrupt the file")
    void testConcurrentSaves() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Warp w = new Warp("Warp" + i, worldId, "world", i, 64, i, 0f, 0f, creatorId, "general", null, false, System.currentTimeMillis());
            futures.add(repository.save(w));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Map<String, Warp> loaded = repository.loadAll().join();
        assertEquals(20, loaded.size());
    }
}
