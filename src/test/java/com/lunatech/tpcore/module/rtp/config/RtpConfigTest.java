package com.lunatech.tpcore.module.rtp.config;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.module.rtp.service.impl.DefaultRtpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class RtpConfigTest {

    @Test
    @DisplayName("Verify RtpConfig default initialization and immutability")
    void testDefaultConfig() {
        RtpConfig config = RtpConfig.createDefault();

        Assertions.assertTrue(config.enabled());
        Assertions.assertEquals(20, config.bufferCapacity());
        Assertions.assertEquals(45.0, config.maxMsptThreshold());
        Assertions.assertEquals(15, config.demandWindowMinutes());

        RtpWorldConfig worldConfig = config.worldConfigs().get("world");
        Assertions.assertNotNull(worldConfig);
        Assertions.assertTrue(worldConfig.enabled());
        Assertions.assertEquals(100, worldConfig.minRadius());
        Assertions.assertEquals(5000, worldConfig.maxRadius());
        Assertions.assertEquals(60, worldConfig.cooldownSeconds());
        Assertions.assertEquals(0, worldConfig.warmupSeconds());

        Assertions.assertNotNull(config.messages());
        Assertions.assertNotNull(config.messages().prefix());
        Assertions.assertNotNull(config.messages().teleportSuccess());
    }
    @Test void squareAndCircleSamplesStayInsideTheAnnulus() {
        for (String shape : List.of("SQUARE", "CIRCLE")) {
            var policy = new RtpWorldConfig("world", true, 999, 1000, -400, 200, shape, List.of(), List.of(), 30, 0, 0, 0, false);
            for (int index = 0; index < 10000; index++) {
                var point = DefaultRtpService.randomPoint(policy);
                Assertions.assertTrue(policy.contains(point[0], point[1]));
            }
        }
    }
    @Test void invalidReloadRetainsTheLastValidSnapshot(@TempDir Path directory) throws Exception {
        var manager = new ModularConfigManager(directory, LoggerFactory.getLogger(getClass()), getClass().getClassLoader());
        manager.initializeDirectories();
        var configs = new RtpConfigManager(manager, LoggerFactory.getLogger(getClass()));
        var initial = configs.load(); Assertions.assertNotNull(initial);
        Assertions.assertEquals(20, initial.runtime().timeoutSeconds());
        Assertions.assertEquals(64, initial.runtime().cacheRetentionPerWorld());
        Files.writeString(directory.resolve("modules/rtp.yml"), "buffer-capacity: -3\n");
        Assertions.assertNull(configs.load());
        Assertions.assertSame(initial, configs.getActiveConfig());
        Files.writeString(directory.resolve("modules/rtp.yml"), "world-configs:\n  world:\n    min-radius: 200\n    max-radius: 100\n");
        Assertions.assertNull(configs.load());
        Assertions.assertSame(initial, configs.getActiveConfig());
    }

}
