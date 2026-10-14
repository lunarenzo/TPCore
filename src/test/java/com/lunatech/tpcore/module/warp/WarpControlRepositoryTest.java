package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig.WarpControlConfig;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.sql.DriverManager;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class WarpControlRepositoryTest {
    @TempDir File directory;
    private List<WarpRepository> repositories() {
        var logger = LoggerFactory.getLogger("WarpControlRepositoryTest");
        var control = new WarpControlConfig(30, 2, 7, 256);
        return List.of(new SqliteWarpRepository(directory, logger, 16, 100, control),
            new YamlWarpRepository(directory, logger, 16, 100, 16777216, control));
    }
    private Warp warp(String name) {
        return new Warp(name, UUID.randomUUID(), "world", -5, 70, 2, 90, 10, UUID.randomUUID(), "market",
            "name-sha256:original:hash", true, 1234, true, "Unsafe <red>bridge", true);
    }
    private DeletedWarp deleted(Warp warp, long timestamp) { return new DeletedWarp(UUID.randomUUID(), warp, timestamp, UUID.randomUUID()); }

    @Test void archiveAndRestoreRoundTripPreservesEveryFieldAndConsumesTicket() {
        for (WarpRepository repo : repositories()) {
            try {
                repo.initialize().join(); Warp original = warp("hub"); repo.save(original).join();
                DeletedWarp entry = deleted(original, System.currentTimeMillis());
                repo.archiveAndDelete(entry).join();
                assertTrue(repo.loadAll().join().isEmpty()); assertEquals(List.of(entry), repo.loadDeleted().join());
                repo.restore(entry).join();
                assertEquals(original, repo.loadAll().join().get("hub")); assertTrue(repo.loadDeleted().join().isEmpty());
                assertThrows(RuntimeException.class, () -> repo.restore(entry).join());
            } finally { repo.close().join(); }
        }
    }

    @Test void collisionAndStaleSnapshotFailuresAreAtomic() {
        for (WarpRepository repo : repositories()) {
            try {
                repo.initialize().join(); Warp original = warp("hub"), replacement = warp("hub"); repo.save(original).join();
                assertThrows(RuntimeException.class, () -> repo.archiveAndDelete(deleted(replacement, System.currentTimeMillis())).join());
                assertEquals(original, repo.loadAll().join().get("hub")); assertTrue(repo.loadDeleted().join().isEmpty());
                DeletedWarp entry = deleted(original, System.currentTimeMillis()); repo.archiveAndDelete(entry).join(); repo.save(replacement).join();
                assertThrows(RuntimeException.class, () -> repo.restore(entry).join());
                assertEquals(replacement, repo.loadAll().join().get("hub")); assertEquals(List.of(entry), repo.loadDeleted().join());
            } finally { repo.close().join(); }
        }
    }

    @Test void retentionAndCapacityApplyToMergedMigrationHistory() {
        for (WarpRepository repo : repositories()) {
            try {
                repo.initialize().join(); long now = System.currentTimeMillis();
                DeletedWarp oldest = deleted(warp("oldest"), now - 3000), middle = deleted(warp("middle"), now - 2000), newest = deleted(warp("newest"), now - 1000);
                DeletedWarp expired = deleted(warp("expired"), now - TimeUnit.DAYS.toMillis(8));
                repo.saveDeleted(List.of(oldest)).join(); repo.saveDeleted(List.of(expired, middle, newest)).join();
                assertEquals(List.of(newest, middle), repo.loadDeleted().join());
                assertThrows(RuntimeException.class, () -> repo.restore(oldest).join());
                assertTrue(repo.loadAll().join().isEmpty());
            } finally { repo.close().join(); }
        }
    }

    @Test void archiveAndFlagsSurviveRepositoryReopen() {
        List<WarpRepository> initial = repositories();
        Warp original = warp("hub"); DeletedWarp entry = deleted(original, System.currentTimeMillis());
        for (WarpRepository repo : initial) {
            try { repo.initialize().join(); repo.save(original).join(); repo.archiveAndDelete(entry).join(); }
            finally { repo.close().join(); }
        }
        for (WarpRepository repo : repositories()) {
            try { repo.initialize().join(); assertEquals(List.of(entry), repo.loadDeleted().join()); repo.restore(entry).join(); assertEquals(original, repo.loadAll().join().get("hub")); }
            finally { repo.close().join(); }
        }
    }

    @Test void oldSqliteSchemaUpgradesWithoutChangingExistingWarp() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + new File(directory, "tpcore.db"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tpcore_warps (id INTEGER PRIMARY KEY, warp_name TEXT COLLATE NOCASE UNIQUE NOT NULL, world_id TEXT, world_name TEXT, x DOUBLE, y DOUBLE, z DOUBLE, yaw FLOAT, pitch FLOAT, creator_uuid TEXT, category TEXT, password_hash TEXT, permission_gated BOOLEAN, created_at BIGINT)");
            statement.execute("INSERT INTO tpcore_warps VALUES (1,'hub',NULL,'world',-5,70,2,90,10,NULL,'market','oldhash',1,1234)");
        }
        WarpRepository repo = new SqliteWarpRepository(directory, LoggerFactory.getLogger("LegacySqliteTest"));
        try {
            repo.initialize().join(); Warp loaded = repo.loadAll().join().get("hub");
            assertFalse(loaded.closed()); assertFalse(loaded.unlisted()); assertEquals("", loaded.closureReason());
            assertEquals("oldhash", loaded.passwordHash()); assertTrue(loaded.permissionGated()); assertEquals(1234, loaded.createdAt());
            repo.archiveAndDelete(deleted(loaded, System.currentTimeMillis())).join(); assertEquals(1, repo.loadDeleted().join().size());
        } finally { repo.close().join(); }
    }

    @Test void legacyYamlDefaultsRemainOpenListedAndCanBeArchived() throws Exception {
        File folder = new File(directory, "warps"); assertTrue(folder.mkdirs());
        Files.writeString(new File(folder, "warps.yml").toPath(), "warps:\n  hub:\n    world: world\n    x: -5\n    y: 70\n    z: 2\n    category: market\n");
        WarpRepository repo = new YamlWarpRepository(directory, LoggerFactory.getLogger("LegacyYamlTest"));
        try {
            repo.initialize().join(); Warp loaded = repo.loadAll().join().get("hub");
            assertFalse(loaded.closed()); assertFalse(loaded.unlisted()); assertEquals(0, loaded.createdAt());
            repo.archiveAndDelete(deleted(loaded, System.currentTimeMillis())).join(); assertEquals(1, repo.loadDeleted().join().size());
        } finally { repo.close().join(); }
    }
}
