package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.impl.YamlBackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlBackRepositoryTest {

    @TempDir
    File tempDir;

    private YamlBackRepository repository;
    private final UUID playerUuid = UUID.randomUUID();
    private final UUID worldUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new YamlBackRepository(tempDir, LoggerFactory.getLogger("Test-YamlBackRepo"));
        repository.initialize().join();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close().join();
        }
    }

    @Test
    @DisplayName("YamlBackRepository correctly saves and loads player history as a YAML sequence")
    void testSaveAndLoadPlayerHistory() {
        BackLocation loc1 = new BackLocation(worldUuid, "world", 100.5, 64.0, 200.5, 90f, 0f, 1000L, BackCause.TELEPORT);
        BackLocation loc2 = new BackLocation(worldUuid, "world_nether", 300.0, 70.0, 400.0, 180f, 15f, 2000L, BackCause.DEATH);

        repository.savePlayerHistory(playerUuid, List.of(loc1, loc2)).join();

        List<BackLocation> loaded = repository.loadPlayerHistory(playerUuid).join();
        assertEquals(2, loaded.size());

        assertEquals(100.5, loaded.get(0).x(), 0.001);
        assertEquals("world", loaded.get(0).worldName());
        assertEquals(BackCause.TELEPORT, loaded.get(0).cause());

        assertEquals(300.0, loaded.get(1).x(), 0.001);
        assertEquals("world_nether", loaded.get(1).worldName());
        assertEquals(BackCause.DEATH, loaded.get(1).cause());
    }

    @Test
    @DisplayName("YamlBackRepository saveAll batch saves and loads all player histories")
    void testSaveAllBatch() {
        UUID player2 = UUID.randomUUID();
        BackLocation loc1 = new BackLocation(worldUuid, "world", 10.0, 64.0, 10.0, 0f, 0f, 1000L, BackCause.TELEPORT);
        BackLocation loc2 = new BackLocation(worldUuid, "world_the_end", 20.0, 64.0, 20.0, 0f, 0f, 2000L, BackCause.PORTAL);

        repository.saveAll(Map.of(
            playerUuid, List.of(loc1),
            player2, List.of(loc2)
        )).join();

        Map<UUID, List<BackLocation>> all = repository.loadAll().join();
        assertEquals(2, all.size());
        assertTrue(all.containsKey(playerUuid));
        assertTrue(all.containsKey(player2));

        assertEquals(10.0, all.get(playerUuid).get(0).x(), 0.001);
        assertEquals(20.0, all.get(player2).get(0).x(), 0.001);
    }
}
