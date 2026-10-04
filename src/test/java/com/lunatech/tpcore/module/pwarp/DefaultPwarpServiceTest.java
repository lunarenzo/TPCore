package com.lunatech.tpcore.module.pwarp;

import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.cache.impl.DefaultPwarpCache;
import com.lunatech.tpcore.module.pwarp.config.PwarpConfig;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRepository;
import com.lunatech.tpcore.module.pwarp.repository.impl.SqlitePwarpRepository;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import com.lunatech.tpcore.module.pwarp.service.impl.DefaultPwarpService;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefaultPwarpServiceTest {

    @Test
    @DisplayName("Verify DefaultPwarpService initialization, warp queries, and cache sync")
    void testServiceInitializationAndQueries(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("test_pwarps_service.db").toFile();
        PwarpRepository repo = new SqlitePwarpRepository(dbFile, LoggerFactory.getLogger("TestLogger"));
        repo.initialize().join();

        PwarpCache cache = new DefaultPwarpCache();
        PwarpConfig config = PwarpConfig.createDefault();

        JavaPlugin mockPlugin = Mockito.mock(JavaPlugin.class);
        Logger mockLogger = LoggerFactory.getLogger("TestLogger");
        Mockito.when(mockPlugin.getSLF4JLogger()).thenReturn(mockLogger);
        Mockito.when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());

        PwarpService service = new DefaultPwarpService(mockPlugin, () -> config, repo, cache);

        UUID owner = UUID.randomUUID();
        Pwarp warp1 = new Pwarp(1, owner, "Player1", "shop", "Shop warp", "world", 100, 64, -200, 0, 0, "CHEST", "shops", false, System.currentTimeMillis(), 0);
        repo.savePwarp(warp1).join();

        // Initialize service (loads DB into cache)
        service.initialize().join();

        Optional<Pwarp> loaded = service.getWarp("shop");
        Assertions.assertTrue(loaded.isPresent());
        Assertions.assertEquals("shop", loaded.get().name());
        Assertions.assertEquals("shops", loaded.get().category());

        List<Pwarp> publicWarps = service.getPublicWarps();
        Assertions.assertEquals(1, publicWarps.size());

        List<Pwarp> playerWarps = service.getPlayerWarps(owner);
        Assertions.assertEquals(1, playerWarps.size());

        service.shutdown();
        repo.close().join();
    }
}
