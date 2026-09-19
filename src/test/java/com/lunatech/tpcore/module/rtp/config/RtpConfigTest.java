package com.lunatech.tpcore.module.rtp.config;

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
}
