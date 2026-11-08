package com.lunatech.tpcore.module.home.repository.impl;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class SqliteHomeRepositoryTest {
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID();
    private final UUID world = UUID.randomUUID();
    private static final long RETENTION = TimeUnit.DAYS.toMillis(7);

    @Test
    void concurrentSavesEnforceLimitAndExplicitOverwrite() {
        var repo = repository();
        try {
            var first = repo.save(home("Base", 1), false, 1);
            var second = repo.save(home("Mine", 2), false, 1);
            assertEquals(HomeResultStatus.SUCCESS, first.join());
            assertEquals(HomeResultStatus.LIMIT_REACHED, second.join());
            assertEquals(HomeResultStatus.OVERWRITE_REQUIRED, repo.save(home("base", 2), false, 1).join());
            assertEquals(List.of(home("Base", 1)), repo.list(owner, 5, 0).join());
            assertEquals(HomeResultStatus.SUCCESS, repo.save(home("base", 2), true, 1).join());
            Home updated = repo.findByName(owner, "BASE").join().orElseThrow();
            assertEquals(2.0, updated.x());
            assertEquals(1L, updated.createdAt());
            assertEquals(world, updated.worldId());
        } finally { repo.close().join(); }
    }

    @Test
    void deletionAndArchiveRollbackTogetherAndRestoreHonorsLimits() throws Exception {
        var repo = repository();
        try {
            var base = home("Base", 1);
            assertEquals(HomeResultStatus.SUCCESS, repo.save(base, false, 1).join());
            try (var connection = DriverManager.getConnection(url()); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_home_delete BEFORE DELETE ON tpcore_homes "
                    + "BEGIN SELECT RAISE(ABORT, 'test failure'); END");
                assertThrows(CompletionException.class, () -> repo.archive(owner, "Base", 10, RETENTION).join());
                assertEquals(base, repo.findByName(owner, "Base").join().orElseThrow());
                statement.execute("DROP TRIGGER reject_home_delete");
            }
            assertEquals(HomeResultStatus.SUCCESS, repo.archive(owner, "base", 10, RETENTION).join());
            assertTrue(repo.findByName(owner, "base").join().isEmpty());
            assertEquals(HomeResultStatus.LIMIT_REACHED, repo.restore(owner, "base", 0, RETENTION).join());
            assertEquals(HomeResultStatus.SUCCESS, repo.restore(owner, "base", 1, RETENTION).join());
            assertEquals(base, repo.findByName(owner, "base").join().orElseThrow());
            assertEquals(HomeResultStatus.OVERWRITE_REQUIRED, repo.restore(owner, "base", 1, RETENTION).join());
            try (var connection = DriverManager.getConnection(url()); var statement = connection.createStatement()) {
                statement.execute("DROP TABLE tpcore_homes");
            }
            assertThrows(CompletionException.class, () -> repo.findByName(owner, "base").join());
        } finally { repo.close().join(); }
    }

    @Test
    void legacyRowsKeepTheirCoordinatesAndSharedMetadataThroughArchiveAndRestore() throws Exception {
        try (var connection = DriverManager.getConnection(url()); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE tpcore_homes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid VARCHAR(36) NOT NULL,
                    home_name VARCHAR(32) COLLATE NOCASE NOT NULL, world_name VARCHAR(64) NOT NULL,
                    x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL,
                    pitch FLOAT NOT NULL, created_at BIGINT NOT NULL, shared_with TEXT DEFAULT '',
                    UNIQUE(owner_uuid, home_name))
                """);
            try (var insert = connection.prepareStatement("INSERT INTO tpcore_homes VALUES (1,?, 'Base','old_world',1,64,2,0,0,123,'legacy')")) {
                insert.setString(1, owner.toString());
                insert.executeUpdate();
            }
        }
        var repo = repository();
        try {
            Home legacy = repo.findByName(owner, "base").join().orElseThrow();
            assertEquals("old_world", legacy.worldName());
            assertNull(legacy.worldId());
            assertEquals(HomeResultStatus.SUCCESS, repo.archive(owner, "base", 10, RETENTION).join());
            assertEquals(HomeResultStatus.SUCCESS, repo.restore(owner, "base", 1, RETENTION).join());
            assertEquals(legacy, repo.findByName(owner, "base").join().orElseThrow());
            try (var connection = DriverManager.getConnection(url()); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT shared_with FROM tpcore_homes")) {
                assertTrue(result.next());
                assertEquals("legacy", result.getString(1));
            }
        } finally { repo.close().join(); }
    }

    private String url() { return "jdbc:sqlite:" + directory.resolve("tpcore.db"); }

    private SqliteHomeRepository repository() {
        var repo = new SqliteHomeRepository(directory.toFile(), LoggerFactory.getLogger(getClass()), 8);
        try {
            repo.initialize().join();
            return repo;
        } catch (RuntimeException failure) {
            repo.close().join();
            throw failure;
        }
    }

    private Home home(String name, long coordinate) {
        return new Home(owner, name, "world", world, coordinate, 64, 0, 0, 0, coordinate);
    }
}
