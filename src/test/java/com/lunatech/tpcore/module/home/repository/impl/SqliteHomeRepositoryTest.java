package com.lunatech.tpcore.module.home.repository.impl;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
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
    void menuPagesClampAndStaleRowsCannotBeUsedOrArchived() {
        var repo = repository();
        try {
            var empty = repo.page(owner, 50).join();
            assertEquals(0, empty.total());
            assertEquals(1, empty.page());
            for (int index = 28; index >= 0; index--) {
                assertEquals(HomeResultStatus.SUCCESS, repo.save(home("base%02d".formatted(index), index), false, 29).join());
            }
            var first = repo.page(owner, 1).join();
            assertEquals(29, first.total());
            assertEquals(28, first.entries().size());
            assertEquals("base00", first.entries().getFirst().home().name());
            var last = repo.page(owner, 2).join();
            var entry = last.entries().getFirst();
            assertEquals("base28", entry.home().name());
            assertEquals(entry.home(), repo.findMatching(entry).join().orElseThrow());
            assertEquals(HomeResultStatus.SUCCESS, repo.save(home("base28", 300), true, 29).join());
            assertTrue(repo.findMatching(entry).join().isEmpty());
            assertEquals(HomeResultStatus.HOME_CHANGED, repo.archiveMatching(entry, 10, RETENTION).join());
            var updated = repo.page(owner, 2).join().entries().getFirst();
            assertEquals(entry.rowId(), updated.rowId());
            assertEquals(HomeResultStatus.SUCCESS, repo.archiveMatching(updated, 10, RETENTION).join());
            assertEquals(1, repo.page(owner, 2).join().page());
            assertEquals(HomeResultStatus.SUCCESS, repo.restore(owner, "base28", 29, RETENTION).join());
            assertTrue(repo.findMatching(updated).join().isEmpty());
            assertEquals(HomeResultStatus.HOME_CHANGED, repo.archiveMatching(updated, 10, RETENTION).join());
        } finally { repo.close().join(); }
    }

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
    void suggestionsUseLiteralPrefixAcrossTheFullCollectionAndCoalesceQueuedInput() throws Exception {
        var repo = repository();
        try (var connection = DriverManager.getConnection(url()); var statement = connection.createStatement()) {
            for (String name : List.of("alpha", "mine-2", "mine_1", "Mine_2")) repo.save(home(name, 1), false, 10).join();
            statement.execute("BEGIN IMMEDIATE");
            var blocker = repo.archive(owner, "absent", 10, RETENTION);
            var old = repo.suggestNames(owner, "m", 1);
            var newest = repo.suggestNames(owner, "MINE_", 2);
            assertEquals(List.of(), old.get(1, TimeUnit.SECONDS));
            var save = repo.save(home("z", 3), false, 10);
            statement.execute("COMMIT");
            assertEquals(HomeResultStatus.HOME_NOT_FOUND, blocker.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("mine_1", "Mine_2"), newest.get(2, TimeUnit.SECONDS));
            assertEquals(HomeResultStatus.SUCCESS, save.get(2, TimeUnit.SECONDS));
        } finally { repo.close().join(); }
    }

    @Test
    void shutdownDrainsAcceptedWritesAfterSqliteContentionClears() throws Exception {
        var repo = repository();
        try (var connection = DriverManager.getConnection(url()); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            var write = repo.archive(owner, "absent", 10, RETENTION);
            var save = repo.save(home("base", 1), false, 10);
            var close = repo.close();
            assertFalse(close.isDone());
            statement.execute("COMMIT");
            assertEquals(HomeResultStatus.HOME_NOT_FOUND, write.get(3, TimeUnit.SECONDS));
            assertEquals(HomeResultStatus.SUCCESS, save.get(3, TimeUnit.SECONDS));
            close.get(3, TimeUnit.SECONDS);
        } finally { repo.close().join(); }
        var reopened = repository();
        try { assertTrue(reopened.findByName(owner, "base").join().isPresent()); }
        finally { reopened.close().join(); }
    }

    @Test
    void drainDeadlineRejectsQueuedWritesWithoutClosingAnActiveWorker() throws Exception {
        var repo = new SqliteHomeRepository(directory.toFile(), LoggerFactory.getLogger(getClass()), 8, 1);
        repo.initialize().join();
        try (var connection = DriverManager.getConnection(url()); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            var blocker = repo.archive(owner, "absent", 10, RETENTION);
            var queued = repo.save(home("base", 1), false, 10);
            var close = repo.close();
            assertInstanceOf(RejectedExecutionException.class,
                assertThrows(ExecutionException.class, () -> queued.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(close.isDone());
            statement.execute("COMMIT");
            assertEquals(HomeResultStatus.HOME_NOT_FOUND, blocker.get(3, TimeUnit.SECONDS));
            close.get(3, TimeUnit.SECONDS);
        } finally { repo.close().join(); }
        var reopened = repository();
        try { assertTrue(reopened.findByName(owner, "base").join().isEmpty()); }
        finally { reopened.close().join(); }
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
        var repo = new SqliteHomeRepository(directory.toFile(), LoggerFactory.getLogger(getClass()), 8, 10);
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
