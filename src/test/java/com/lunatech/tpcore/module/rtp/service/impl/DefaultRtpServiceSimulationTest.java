package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpWorldConfig;
import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class DefaultRtpServiceSimulationTest {

    @Mock
    private JavaPlugin plugin;

    @Mock
    private Server server;

    @Mock
    private RegionScheduler regionScheduler;

    @Mock
    private EntityScheduler entityScheduler;

    @Mock
    private AdaptiveRtpReplenisher replenisher;

    @Mock
    private RtpSafetyInspector safetyInspector;

    @Mock
    private RtpChunkTicketManager ticketManager;

    @Mock
    private Player player;

    @Mock
    private World world;

    private DefaultRtpService rtpService;
    private RtpConfig rtpConfig;
    private final UUID worldUuid = UUID.randomUUID();
    private final UUID playerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        setBukkitServer(this.server);
        Mockito.lenient().when(this.server.getRegionScheduler()).thenReturn(this.regionScheduler);
        Mockito.lenient().when(this.player.getUniqueId()).thenReturn(this.playerUuid);
        Mockito.lenient().when(this.player.getWorld()).thenReturn(this.world);
        Mockito.lenient().when(this.player.isOnline()).thenReturn(true);
        Mockito.lenient().when(this.player.hasPermission(Permissions.RTP_USE)).thenReturn(true);
        Mockito.lenient().when(this.player.getScheduler()).thenReturn(this.entityScheduler);
        Mockito.lenient().when(this.world.getName()).thenReturn("world");
        Mockito.lenient().when(this.world.getUID()).thenReturn(this.worldUuid);

        // Immediately execute task passed to EntityScheduler.run
        Mockito.lenient().when(this.entityScheduler.run(
            Mockito.any(),
            Mockito.any(),
            Mockito.nullable(Runnable.class)
        )).thenAnswer(invocation -> {
            Consumer task = invocation.getArgument(1);
            if (task != null) {
                task.accept(null);
            }
            return null;
        });

        RtpWorldConfig worldConfig = new RtpWorldConfig(
            "world",
            true,
            100,
            5000,
            0,
            0,
            "CIRCLE",
            List.of(),
            List.of(),
            30,
            0, // cooldown
            0, // warmup
            0.0,
            true // useChunkTickets
        );

        this.rtpConfig = new RtpConfig(
            true,
            20,
            45.0,
            15,
            Map.of("world", worldConfig),
            RtpConfig.RtpMessages.createDefault()
        );

        Supplier<RtpConfig> configSupplier = () -> this.rtpConfig;
        this.rtpService = new DefaultRtpService(
            this.plugin,
            configSupplier,
            this.replenisher,
            this.safetyInspector,
            this.ticketManager
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        setBukkitServer(null);
    }

    private static void setBukkitServer(Server server) throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    @Test
    @DisplayName("Simulation Test: executeRtp successfully teleports player to popped candidate")
    void testExecuteRtpSuccessfulTeleport() throws Exception {
        RtpCandidate candidate = new RtpCandidate(250.5, 70.0, -150.5, 45.0f, 0.0f, this.worldUuid, System.currentTimeMillis());
        Mockito.when(this.replenisher.popCandidate(this.world)).thenReturn(candidate);
        Mockito.when(this.safetyInspector.inspectCandidate(
            Mockito.eq(this.world),
            Mockito.any(RtpWorldConfig.class),
            Mockito.eq(250),
            Mockito.eq(-151),
            Mockito.eq(true)
        )).thenReturn(CompletableFuture.completedFuture(candidate));

        Mockito.when(this.player.teleportAsync(Mockito.any(Location.class), Mockito.eq(TeleportCause.PLUGIN))).thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<Boolean> future = this.rtpService.executeRtp(this.player);
        Boolean result = future.get();

        Assertions.assertTrue(result, "RTP execution future should complete with true");

        // Verify player.teleportAsync call
        ArgumentCaptor<Location> locCaptor = ArgumentCaptor.forClass(Location.class);
        Mockito.verify(this.player).teleportAsync(locCaptor.capture(), Mockito.eq(TeleportCause.PLUGIN));

        Location dest = locCaptor.getValue();
        Assertions.assertEquals(this.world, dest.getWorld());
        Assertions.assertEquals(250.5, dest.getX());
        Assertions.assertEquals(70.0, dest.getY());
        Assertions.assertEquals(-150.5, dest.getZ());
        Assertions.assertEquals(45.0f, dest.getYaw());

        // Verify ticket management
        Mockito.verify(this.ticketManager).addCandidateTickets(Mockito.eq(this.world), Mockito.anyLong());
        Mockito.verify(this.player).sendMessage(Mockito.any(Component.class));
    }

    @Test
    @DisplayName("Simulation Test: On-demand fallback when candidate pool is empty")
    void testExecuteRtpOnDemandFallback() throws Exception {
        RtpCandidate safeCandidate = new RtpCandidate(120.0, 65.0, 300.0, 0.0f, 0.0f, this.worldUuid, System.currentTimeMillis());
        Mockito.when(this.replenisher.popCandidate(this.world)).thenReturn(null);
        Mockito.when(this.safetyInspector.inspectCandidate(
            Mockito.eq(this.world),
            Mockito.any(RtpWorldConfig.class),
            Mockito.anyInt(),
            Mockito.anyInt(),
            Mockito.eq(true)
        )).thenReturn(CompletableFuture.completedFuture(safeCandidate));

        Mockito.when(this.player.teleportAsync(Mockito.any(Location.class), Mockito.eq(TeleportCause.PLUGIN))).thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<Boolean> future = this.rtpService.executeRtp(this.player);
        Boolean result = future.get();

        Assertions.assertTrue(result, "RTP on-demand fallback should complete with true");

        // Verify record demand call
        Mockito.verify(this.replenisher).recordDemand(this.world);
        Mockito.verify(this.player).teleportAsync(Mockito.any(Location.class), Mockito.eq(TeleportCause.PLUGIN));
    }

    @Test
    @DisplayName("Simulation Test: Retry mechanism when candidate is unsafe")
    void testExecuteRtpUnsafeRetry() throws Exception {
        RtpCandidate unsafeCandidate = new RtpCandidate(50.0, 60.0, 50.0, 0.0f, 0.0f, this.worldUuid, System.currentTimeMillis());
        RtpCandidate safeCandidate = new RtpCandidate(300.0, 68.0, 400.0, 90.0f, 0.0f, this.worldUuid, System.currentTimeMillis());

        Mockito.when(this.replenisher.popCandidate(this.world))
            .thenReturn(unsafeCandidate)
            .thenReturn(safeCandidate);

        // First candidate inspection returns null (unsafe)
        Mockito.when(this.safetyInspector.inspectCandidate(
            Mockito.eq(this.world),
            Mockito.any(RtpWorldConfig.class),
            Mockito.eq(50),
            Mockito.eq(50),
            Mockito.eq(true)
        )).thenReturn(CompletableFuture.completedFuture(null));

        // Second candidate inspection returns safe candidate
        Mockito.when(this.safetyInspector.inspectCandidate(
            Mockito.eq(this.world),
            Mockito.any(RtpWorldConfig.class),
            Mockito.eq(300),
            Mockito.eq(400),
            Mockito.eq(true)
        )).thenReturn(CompletableFuture.completedFuture(safeCandidate));

        Mockito.when(this.player.teleportAsync(Mockito.any(Location.class), Mockito.eq(TeleportCause.PLUGIN))).thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<Boolean> future = this.rtpService.executeRtp(this.player);
        Boolean result = future.get();

        Assertions.assertTrue(result, "RTP should succeed after retrying safe candidate");

        // Verify 2 pop candidate attempts
        Mockito.verify(this.replenisher, Mockito.times(2)).popCandidate(this.world);

        // Verify player teleported to safe candidate
        ArgumentCaptor<Location> locCaptor = ArgumentCaptor.forClass(Location.class);
        Mockito.verify(this.player).teleportAsync(locCaptor.capture(), Mockito.eq(TeleportCause.PLUGIN));
        Assertions.assertEquals(300.0, locCaptor.getValue().getX());
        Assertions.assertEquals(68.0, locCaptor.getValue().getY());
    }

    @Test
    @DisplayName("Simulation Test: Cooldown blocks consecutive RTP requests")
    void testExecuteRtpCooldownBlock() throws Exception {
        RtpWorldConfig cooldownWorldConfig = new RtpWorldConfig(
            "world",
            true,
            100,
            5000,
            0,
            0,
            "CIRCLE",
            List.of(),
            List.of(),
            30,
            30, // 30 seconds cooldown
            0,
            0.0,
            true // useChunkTickets
        );

        RtpConfig cooldownConfig = new RtpConfig(
            true,
            20,
            45.0,
            15,
            Map.of("world", cooldownWorldConfig),
            RtpConfig.RtpMessages.createDefault()
        );

        Supplier<RtpConfig> supplier = () -> cooldownConfig;
        DefaultRtpService serviceWithCooldown = new DefaultRtpService(
            this.plugin,
            supplier,
            this.replenisher,
            this.safetyInspector,
            this.ticketManager
        );

        RtpCandidate candidate = new RtpCandidate(100.0, 64.0, 100.0, 0f, 0f, this.worldUuid, System.currentTimeMillis());
        Mockito.when(this.replenisher.popCandidate(this.world)).thenReturn(candidate);
        Mockito.when(this.safetyInspector.inspectCandidate(
            Mockito.eq(this.world),
            Mockito.any(RtpWorldConfig.class),
            Mockito.eq(100),
            Mockito.eq(100),
            Mockito.eq(true)
        )).thenReturn(CompletableFuture.completedFuture(candidate));

        Mockito.when(this.player.teleportAsync(Mockito.any(Location.class), Mockito.eq(TeleportCause.PLUGIN))).thenReturn(CompletableFuture.completedFuture(true));

        // First RTP call -> success
        CompletableFuture<Boolean> firstCall = serviceWithCooldown.executeRtp(this.player);
        Assertions.assertTrue(firstCall.get());

        // Second RTP call immediately after -> blocked by cooldown
        CompletableFuture<Boolean> secondCall = serviceWithCooldown.executeRtp(this.player);
        Assertions.assertFalse(secondCall.get());

        // Verify player received cooldown message
        Mockito.verify(this.player, Mockito.atLeastOnce()).sendMessage(Mockito.any(Component.class));
    }
}
