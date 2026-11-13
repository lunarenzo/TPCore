package com.lunatech.tpcore.module.rtp.repository;

import com.lunatech.tpcore.module.rtp.repository.impl.SqliteRtpCacheRepository;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class SqliteRtpCacheRepositoryHardeningTest {
    @TempDir Path directory;
    @Test void retentionAndShutdownPreserveAcceptedWrites() throws Exception {
        var world = UUID.randomUUID();
        var path = directory.resolve("rtp.db");
        var repo = new SqliteRtpCacheRepository(path.toFile(), LoggerFactory.getLogger(getClass()), 16, 2, 60);
        repo.initialize().join();
        for (int index = 0; index < 10; index++) repo.savePackedLocation(world, index).join();
        assertEquals(2, repo.loadPackedLocations(world).join().size());
        var save = repo.savePackedLocation(world, 99);
        repo.close().get(5, TimeUnit.SECONDS); assertTrue(save.isDone()); assertFalse(save.isCompletedExceptionally());
        var reopened = new SqliteRtpCacheRepository(path.toFile(), LoggerFactory.getLogger(getClass()), 16, 2, 60);
        try {
            reopened.initialize().join();
            assertTrue(reopened.loadPackedLocations(world).join().contains(99L));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM tpcore_rtp_cache")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
        } finally { reopened.close().join(); }
    }
    @Test void blockedDatabaseRejectsExcessWorkInsteadOfGrowingAnUnboundedQueue() throws Exception {
        var path = directory.resolve("busy.db");
        var repo = new SqliteRtpCacheRepository(path.toFile(), LoggerFactory.getLogger(getClass()), 2, 2, 60);
        repo.initialize().join();
        try {
            try (var lock = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = lock.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                var writes = new ArrayList<CompletableFuture<Void>>();
                for (int index = 0; index < 20; index++) writes.add(repo.savePackedLocation(UUID.randomUUID(), index));
                assertTrue(writes.stream().anyMatch(CompletableFuture::isCompletedExceptionally));
                statement.execute("COMMIT");
            }
        } finally { repo.close().get(5, TimeUnit.SECONDS); }
    }
}
