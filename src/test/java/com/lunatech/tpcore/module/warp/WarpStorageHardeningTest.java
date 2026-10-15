package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class WarpStorageHardeningTest {
    @TempDir Path directory;
    private Warp warp(String name, String reason) {
        return new Warp(name, UUID.randomUUID(), "world", .5, 64, .5, 0, 0, UUID.randomUUID(), "general", null, false, 123, false, reason, false);
    }
    @Test void oversizedYamlOutputPreservesReadableOriginalForEveryWritePath() throws Exception {
        var repository = new YamlWarpRepository(directory.toFile(), LoggerFactory.getLogger("bound"), 8, 100, 1024, WarpConfig.createDefault().control());
        try {
            repository.initialize().join(); Warp original = warp("hub", ""); repository.save(original).join();
            Path file = directory.resolve("warps/warps.yml"); byte[] before = Files.readAllBytes(file);
            Warp oversized = warp("oversized", "x".repeat(4000));
            assertThrows(RuntimeException.class, () -> repository.save(oversized).join());
            assertThrows(RuntimeException.class, () -> repository.saveAll(List.of(oversized)).join());
            assertThrows(RuntimeException.class, () -> repository.replace("hub", oversized).join());
            assertThrows(RuntimeException.class, () -> repository.saveDeleted(List.of(new DeletedWarp(UUID.randomUUID(), oversized, System.currentTimeMillis(), null))).join());
            assertThrows(RuntimeException.class, () -> repository.mergeSnapshot(List.of(oversized), List.of()).join());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(Map.of("hub", original), repository.loadAll().join());
            assertFalse(Files.exists(directory.resolve("warps/warps.yml.pending")));
            repository.archiveAndDelete(new DeletedWarp(UUID.randomUUID(), original, System.currentTimeMillis(), null)).join();
            assertTrue(repository.loadAll().join().isEmpty());
        } finally { repository.close().join(); }
    }
    @Test void sqliteHistoryFailureRollsBackActiveWarpChangesInSameTransaction() throws Exception {
        var repository = new SqliteWarpRepository(directory.toFile(), LoggerFactory.getLogger("atomic"), 8, 100, WarpConfig.createDefault().control());
        try {
            repository.initialize().join(); Warp original = warp("hub", ""); repository.save(original).join();
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("tpcore.db")); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_history BEFORE INSERT ON tpcore_deleted_warps BEGIN SELECT RAISE(ABORT, 'history unavailable'); END");
            }
            Warp changed = warp("hub", "new"), incoming = warp("incoming", "");
            var deleted = new DeletedWarp(UUID.randomUUID(), original, System.currentTimeMillis(), null);
            assertThrows(RuntimeException.class, () -> repository.mergeSnapshot(List.of(changed, incoming), List.of(deleted)).join());
            assertEquals(Map.of("hub", original), repository.loadAll().join()); assertTrue(repository.loadDeleted().join().isEmpty());
        } finally { repository.close().join(); }
    }
    @Test void bothRepositoriesMergeActiveAndRecoveryDataTogether() {
        var logger = LoggerFactory.getLogger("merge");
        for (var repository : List.of(new SqliteWarpRepository(directory.toFile(), logger, 8, 100, WarpConfig.createDefault().control()), new YamlWarpRepository(directory.toFile(), logger, 8, 100, 16777216, WarpConfig.createDefault().control()))) {
            try {
                repository.initialize().join(); Warp existing = warp("existing", ""), incoming = warp("incoming", "");
                repository.save(existing).join(); var history = new DeletedWarp(UUID.randomUUID(), warp("deleted", ""), System.currentTimeMillis(), null);
                repository.mergeSnapshot(List.of(incoming), List.of(history)).join();
                assertEquals(Map.of("existing", existing, "incoming", incoming), repository.loadAll().join());
                assertEquals(List.of(history), repository.loadDeleted().join());
            } finally { repository.close().join(); }
        }
    }
}
