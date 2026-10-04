package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteWarpRepositoryTest {

    @TempDir
    File tempDir;

    private SqliteWarpRepository repository;
    private final UUID worldId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new SqliteWarpRepository(tempDir, LoggerFactory.getLogger("Test-SqliteWarpRepo"));
        repository.initialize().join();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close().join();
        }
    }

    @Test
    @DisplayName("SqliteWarpRepository saves, finds by name, and loads all warps")
    void testSaveAndFindByName() {
        Warp warp = new Warp("Spawn", worldId, "world", 100.5, 64.0, 200.5, 90f, 0f, creatorId, "general", "hash123", true, 1000L);
        repository.save(warp).join();

        Optional<Warp> found = repository.findByName("spawn").join();
        assertTrue(found.isPresent());
        assertEquals("Spawn", found.get().name());
        assertEquals(100.5, found.get().x(), 0.001);
        assertEquals("general", found.get().category());
        assertTrue(found.get().permissionGated());

        Map<String, Warp> all = repository.loadAll().join();
        assertEquals(1, all.size());
        assertTrue(all.containsKey("spawn"));
    }

    @Test
    @DisplayName("SqliteWarpRepository findByCategory retrieves all warps in category")
    void testFindByCategory() {
        Warp w1 = new Warp("Shop1", worldId, "world", 10, 64, 10, 0f, 0f, creatorId, "market", null, false, 1000L);
        Warp w2 = new Warp("Shop2", worldId, "world", 20, 64, 20, 0f, 0f, creatorId, "market", null, false, 1000L);
        Warp w3 = new Warp("Arena", worldId, "world", 30, 64, 30, 0f, 0f, creatorId, "pvp", null, false, 1000L);

        repository.save(w1).join();
        repository.save(w2).join();
        repository.save(w3).join();

        List<Warp> marketWarps = repository.findByCategory("market").join();
        assertEquals(2, marketWarps.size());

        List<Warp> pvpWarps = repository.findByCategory("pvp").join();
        assertEquals(1, pvpWarps.size());
    }

    @Test
    @DisplayName("SqliteWarpRepository saveAll batch saves multiple warps atomically")
    void testSaveAll() {
        Warp w1 = new Warp("Warp1", worldId, "world", 1, 64, 1, 0f, 0f, creatorId, "general", null, false, 1000L);
        Warp w2 = new Warp("Warp2", worldId, "world", 2, 64, 2, 0f, 0f, creatorId, "general", null, false, 1000L);
        Warp w3 = new Warp("Warp3", worldId, "world", 3, 64, 3, 0f, 0f, creatorId, "vip", null, false, 1000L);

        repository.saveAll(List.of(w1, w2, w3)).join();

        Map<String, Warp> all = repository.loadAll().join();
        assertEquals(3, all.size());
        assertTrue(all.containsKey("warp1"));
        assertTrue(all.containsKey("warp2"));
        assertTrue(all.containsKey("warp3"));
    }

    @Test
    @DisplayName("SqliteWarpRepository delete removes warp case-insensitively")
    void testDelete() {
        Warp warp = new Warp("Spawn", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "general", null, false, 1000L);
        repository.save(warp).join();
        assertTrue(repository.findByName("spawn").join().isPresent());

        repository.delete("SPAWN").join();
        assertFalse(repository.findByName("spawn").join().isPresent());
        assertTrue(repository.loadAll().join().isEmpty());
    }
}
