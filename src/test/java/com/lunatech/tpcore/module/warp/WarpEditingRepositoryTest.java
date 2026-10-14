package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import java.io.File;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class WarpEditingRepositoryTest {
    @TempDir File directory;
    private List<WarpRepository> repositories() {
        var logger = LoggerFactory.getLogger("WarpEditingRepositoryTest");
        return List.of(new SqliteWarpRepository(directory, logger), new YamlWarpRepository(directory, logger));
    }
    private Warp warp(String name) { return new Warp(name, UUID.randomUUID(), "world", -2.5, 64, 3.5, 90, 30, UUID.randomUUID(), "market", "name-sha256:old:hash", true, 12345); }

    @Test void renamePersistsEveryFieldAndRemovesOnlyTheOldKey() {
        for (WarpRepository repository : repositories()) {
            try {
                repository.initialize().join();
                Warp original = warp("old"), unrelated = warp("unrelated"); repository.save(original).join(); repository.save(unrelated).join();
                Warp updated = new Warp("new", original.worldId(), original.worldName(), original.x(), original.y(), original.z(), original.yaw(), original.pitch(),
                    original.creatorUuid(), original.category(), original.passwordHash(), original.permissionGated(), original.createdAt());
                repository.replace("OLD", updated).join();
                assertTrue(repository.findByName("old").join().isEmpty()); assertEquals(updated, repository.findByName("NEW").join().orElseThrow());
                assertEquals(unrelated, repository.findByName("unrelated").join().orElseThrow());
                assertEquals(2, repository.loadAll().join().size());
            } finally { repository.close().join(); }
        }
    }

    @Test void collisionFailsWithoutDeletingEitherWarp() {
        for (WarpRepository repository : repositories()) {
            try {
                repository.initialize().join(); Warp original = warp("old"), target = warp("target");
                repository.save(original).join(); repository.save(target).join();
                assertThrows(RuntimeException.class, () -> repository.replace("old", target).join());
                assertEquals(original, repository.findByName("old").join().orElseThrow()); assertEquals(target, repository.findByName("target").join().orElseThrow());
            } finally { repository.close().join(); }
        }
    }

    @Test void missingSourceFailsAndCaseOnlyRenameRemainsOneWarp() {
        for (WarpRepository repository : repositories()) {
            try {
                repository.initialize().join(); Warp original = warp("old");
                assertThrows(RuntimeException.class, () -> repository.replace("missing", original).join());
                assertTrue(repository.loadAll().join().isEmpty()); repository.save(original).join();
                Warp updated = new Warp("OLD", original.worldId(), original.worldName(), original.x(), original.y(), original.z(), original.yaw(), original.pitch(),
                    original.creatorUuid(), original.category(), original.passwordHash(), original.permissionGated(), original.createdAt());
                repository.replace("old", updated).join();
                assertEquals(1, repository.loadAll().join().size()); assertEquals(updated, repository.findByName("old").join().orElseThrow());
            } finally { repository.close().join(); }
        }
    }
}
