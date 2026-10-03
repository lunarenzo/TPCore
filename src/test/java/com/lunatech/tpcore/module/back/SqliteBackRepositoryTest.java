package com.lunatech.tpcore.module.back;

import com.lunatech.tpcore.module.back.model.BackCause;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.repository.impl.SqliteBackRepository;
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

class SqliteBackRepositoryTest {

    @TempDir
    File tempDir;

    private SqliteBackRepository repository;
    private final UUID playerUuid = UUID.randomUUID();
    private final UUID worldUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new SqliteBackRepository(tempDir, LoggerFactory.getLogger("Test-SqliteBackRepo"));
        repository.initialize().join();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close().join();
        }
    }

    @Test
    @DisplayName("SqliteBackRepository correctly saves and loads player history")
    void testSaveAndLoadPlayerHistory() {
        BackLocation loc1 = new BackLocation(worldUuid, "world", 100.5, 64.0, 200.5, 90f, 0f, 1000L, BackCause.TELEPORT);
        BackLocation loc2 = new BackLocation(worldUuid, "world", 300.0, 70.0, 400.0, 180f, 15f, 2000L, BackCause.DEATH);

        repository.savePlayerHistory(playerUuid, List.of(loc1, loc2)).join();

        List<BackLocation> loaded = repository.loadPlayerHistory(playerUuid).join();
        assertEquals(2, loaded.size());

        assertEquals(100.5, loaded.get(0).x(), 0.001);
        assertEquals(BackCause.TELEPORT, loaded.get(0).cause());

        assertEquals(300.0, loaded.get(1).x(), 0.001);
        assertEquals(BackCause.DEATH, loaded.get(1).cause());
    }

    @Test
    @DisplayName("SqliteBackRepository loadAll and deletePlayerHistory")
    void testLoadAllAndDelete() {
        UUID player2 = UUID.randomUUID();
        BackLocation loc1 = new BackLocation(worldUuid, "world", 10.0, 64.0, 10.0, 0f, 0f, 1000L, BackCause.TELEPORT);
        BackLocation loc2 = new BackLocation(worldUuid, "world", 20.0, 64.0, 20.0, 0f, 0f, 2000L, BackCause.PORTAL);

        repository.savePlayerHistory(playerUuid, List.of(loc1)).join();
        repository.savePlayerHistory(player2, List.of(loc2)).join();

        Map<UUID, List<BackLocation>> all = repository.loadAll().join();
        assertEquals(2, all.size());
        assertTrue(all.containsKey(playerUuid));
        assertTrue(all.containsKey(player2));

        repository.deletePlayerHistory(playerUuid).join();
        List<BackLocation> loaded = repository.loadPlayerHistory(playerUuid).join();
        assertTrue(loaded.isEmpty());
    }
}
