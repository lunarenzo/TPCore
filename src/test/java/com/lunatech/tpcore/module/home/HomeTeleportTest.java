package com.lunatech.tpcore.module.home;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.config.model.HomeRuntimeConfig;
import com.lunatech.tpcore.module.home.economy.HomeEconomy;
import com.lunatech.tpcore.module.home.engine.ConcurrentTeleportPipelineEngine;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeTeleportTest {
    private static final String USE = "tpcore.home.use";

    @Test
    void chunkAndBlockReadsRunOnDestinationAndDispatchRechecksPermission() {
        try (var fixture = new Fixture()) {
            var result = fixture.begin();
            fixture.entityStep();
            fixture.regionStep();
            fixture.chunk.complete(mock(Chunk.class));
            fixture.regionStep();
            when(fixture.player.hasPermission(USE)).thenReturn(false);
            fixture.entityStep();
            assertEquals(HomeResultStatus.NO_PERMISSION, result.join());
            verify(fixture.player, never()).teleportAsync(any(Location.class));
        }
    }

    @Test
    void cancellationDuringChunkLoadPreventsLateDispatch() {
        try (var fixture = new Fixture()) {
            var result = fixture.begin();
            fixture.entityStep();
            fixture.regionStep();
            fixture.service.cancel(fixture.playerId, HomeResultStatus.MOVED);
            assertEquals(HomeResultStatus.MOVED, result.join());
            fixture.chunk.complete(mock(Chunk.class));
            assertTrue(fixture.entityTasks.isEmpty());
            assertTrue(fixture.regionTasks.isEmpty());
            verify(fixture.player, never()).teleportAsync(any(Location.class));
        }
    }

    @Test
    void stalledChunkExpiresAndItsLateCompletionCannotDispatch() throws Exception {
        try (var fixture = new Fixture()) {
            var result = fixture.begin();
            fixture.entityStep();
            fixture.regionStep();
            assertEquals(HomeResultStatus.TIMED_OUT, result.get(10, TimeUnit.SECONDS));
            fixture.chunk.complete(mock(Chunk.class));
            assertTrue(fixture.regionTasks.isEmpty());
            assertFalse(fixture.begin().isDone());
            verify(fixture.player, never()).teleportAsync(any(Location.class));
        }
    }

    @Test
    void nativeDispatchTimeoutRemainsAmbiguousUntilNativeFutureCompletes() throws Exception {
        try (var fixture = new Fixture()) {
            var result = fixture.begin();
            fixture.entityStep();
            fixture.regionStep();
            fixture.chunk.complete(mock(Chunk.class));
            fixture.regionStep();
            fixture.entityStep();
            assertFalse(result.isDone());
            assertEquals(HomeResultStatus.OUTCOME_UNKNOWN, result.get(10, TimeUnit.SECONDS));
            assertEquals(HomeResultStatus.OUTCOME_UNKNOWN, fixture.begin().join());
            fixture.nativeTeleport.complete(true);
            assertFalse(fixture.begin().isDone());
        }
    }

    @Test
    void missingWorldUuidDoesNotFallBackToName() {
        try (var fixture = new Fixture()) {
            fixture.bukkit.when(() -> Bukkit.getWorld(fixture.worldId)).thenReturn(null);
            var result = fixture.begin();
            fixture.entityStep();
            assertEquals(HomeResultStatus.WORLD_UNAVAILABLE, result.join());
            fixture.bukkit.verify(() -> Bukkit.getWorld("world"), never());
        }
    }

    @Test
    void overdueInspectionLeavesOneSpareButCannotGrowNativeWorkUnbounded() {
        try (var fixture = new Fixture()) {
            var engine = new ConcurrentTeleportPipelineEngine(fixture.plugin, 1);
            when(fixture.config.safetyChecks()).thenReturn(new HomeConfig.HomeSafetyConfig(true, true, 128, 1));
            var target = new Location(fixture.world, 64.5, 64, 64.5);
            var first = engine.inspect(target, fixture.config);
            fixture.regionStep();
            assertTrue(first.cancel(false));
            var spare = engine.inspect(target, fixture.config);
            fixture.regionStep();
            assertTrue(spare.cancel(false));
            assertEquals(HomeResultStatus.BUSY, engine.inspect(target, fixture.config).join());
            fixture.chunk.complete(mock(Chunk.class));
            assertFalse(engine.inspect(target, fixture.config).isDone());
        }
    }

    @Test
    void overdueDispatchKeepsPlayerReservedAndAllowsOnlyOneSpare() {
        try (var fixture = new Fixture()) {
            var engine = new ConcurrentTeleportPipelineEngine(fixture.plugin, 1);
            var target = new Location(fixture.world, 64.5, 64, 64.5);
            var first = new CompletableFuture<Boolean>();
            assertTrue(engine.reserveDispatch(fixture.playerId, 1));
            fixture.run(() -> engine.teleport(fixture.player, target, fixture.playerId, first), "entity");
            assertTrue(first.cancel(false));
            assertTrue(engine.isDispatched(fixture.playerId));
            assertFalse(engine.reserveDispatch(fixture.playerId, 1));
            UUID other = UUID.randomUUID();
            var second = new CompletableFuture<Boolean>();
            assertTrue(engine.reserveDispatch(other, 1));
            fixture.run(() -> engine.teleport(fixture.player, target, other, second), "entity");
            assertTrue(second.cancel(false));
            UUID next = UUID.randomUUID();
            assertFalse(engine.reserveDispatch(next, 1));
            fixture.nativeTeleport.complete(true);
            assertFalse(engine.isDispatched(fixture.playerId));
            assertFalse(engine.isDispatched(other));
            assertTrue(engine.reserveDispatch(next, 1));
            engine.releaseDispatch(next);
        }
    }

    @Test
    void pendingRejectedEditPausesThenResumesTravelWhileCommittedEditInvalidatesIt() {
        for (boolean committed : List.of(false, true)) {
            try (var fixture = new Fixture()) {
                var travel = fixture.begin();
                fixture.entityStep();
                fixture.regionStep();
                fixture.chunk.complete(mock(Chunk.class));
                fixture.regionStep();
                var mutation = new CompletableFuture<HomeResultStatus>();
                when(fixture.repository.archive(eq(fixture.playerId), eq("base"), anyInt(), anyLong())).thenReturn(mutation);
                var delete = fixture.service.deleteHome(fixture.player, "base");
                fixture.entityStep();
                assertFalse(travel.isDone());
                verify(fixture.player, never()).teleportAsync(any(Location.class));
                var status = committed ? HomeResultStatus.SUCCESS : HomeResultStatus.HOME_NOT_FOUND;
                mutation.complete(status);
                assertEquals(status, delete.join());
                if (committed) {
                    assertEquals(HomeResultStatus.HOME_CHANGED, travel.join());
                } else {
                    fixture.entityStep();
                    fixture.regionStep();
                    fixture.regionStep();
                    fixture.entityStep();
                    fixture.nativeTeleport.complete(true);
                    assertEquals(HomeResultStatus.SUCCESS, travel.join());
                }
            }
        }
    }

    @Test
    void partialBlockSupportUsesExactFeetAndRejectsUnownedChunkEdges() {
        try (var fixture = new Fixture()) {
            var target = new Location(fixture.world, 64.5, 64.5, 64.5);
            fixture.owner = "region";
            doReturn(block(Material.STONE, new BoundingBox(0, 0, 0, 1, .5, 1)))
                .when(fixture.world).getBlockAt(64, 64, 64);
            assertTrue(ConcurrentTeleportPipelineEngine.isSafe(target, fixture.config));
            doReturn(block(Material.STONE, new BoundingBox(0, 0, 0, .3, .5, 1)))
                .when(fixture.world).getBlockAt(64, 64, 64);
            assertFalse(ConcurrentTeleportPipelineEngine.isSafe(target, fixture.config));
            doReturn(block(Material.STONE, new BoundingBox(0, 0, 0, 1, 1.5, 1)))
                .when(fixture.world).getBlockAt(64, 63, 64);
            target.setY(64);
            assertFalse(ConcurrentTeleportPipelineEngine.isSafe(target, fixture.config));
            target.setX(64.1);
            assertFalse(ConcurrentTeleportPipelineEngine.isSafe(target, fixture.config));
            verify(fixture.world, never()).getBlockAt(eq(63), anyInt(), anyInt());
        }
    }

    private static Block block(Material material, BoundingBox... boxes) {
        var block = mock(Block.class);
        var shape = mock(VoxelShape.class);
        when(block.getType()).thenReturn(material);
        when(block.getCollisionShape()).thenReturn(shape);
        when(shape.getBoundingBoxes()).thenReturn(List.of(boxes));
        return block;
    }

    private static final class Fixture implements AutoCloseable {
        final UUID playerId = UUID.randomUUID();
        final UUID worldId = UUID.randomUUID();
        final Plugin plugin = mock(Plugin.class);
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final ArrayDeque<Runnable> entityTasks = new ArrayDeque<>();
        final ArrayDeque<Runnable> regionTasks = new ArrayDeque<>();
        final CompletableFuture<Chunk> chunk = new CompletableFuture<>();
        final CompletableFuture<Boolean> nativeTeleport = new CompletableFuture<>();
        final org.mockito.MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final HomeRepository repository = mock(HomeRepository.class);
        final HomeEconomy.BankLane bankLane = new HomeEconomy.BankLane();
        final HomeConfig config = mock(HomeConfig.class);
        final DefaultHomeService service;
        String owner = "command";

        Fixture() {
            var defaults = HomeConfig.createDefault();
            when(config.enabled()).thenReturn(true);
            when(config.safetyChecks()).thenReturn(defaults.safetyChecks());
            when(config.worldRestrictions()).thenReturn(defaults.worldRestrictions());
            var runtime = mock(HomeRuntimeConfig.class);
            when(config.runtime()).thenReturn(runtime);
            when(runtime.timeoutSeconds()).thenReturn(4);
            when(runtime.safetyFreshnessMillis()).thenReturn(5000);
            when(runtime.maxCooldowns()).thenReturn(32);
            when(runtime.maxRequests()).thenReturn(4);
            when(runtime.stalledWorkReserve()).thenReturn(1);
            when(runtime.hazards()).thenReturn(defaults.runtime().hazards());

            var entityScheduler = mock(EntityScheduler.class);
            var regionScheduler = mock(RegionScheduler.class);
            var scheduled = mock(ScheduledTask.class);
            when(player.getScheduler()).thenReturn(entityScheduler);
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.hasPermission(USE)).thenReturn(true);
            when(plugin.getSLF4JLogger()).thenReturn(LoggerFactory.getLogger(getClass()));
            when(entityScheduler.run(eq(plugin), any(), any())).thenAnswer(call -> {
                Consumer<ScheduledTask> action = call.getArgument(1);
                entityTasks.add(() -> action.accept(scheduled));
                return scheduled;
            });
            doAnswer(call -> { regionTasks.add(call.getArgument(2)); return null; })
                .when(regionScheduler).execute(eq(plugin), any(Location.class), any(Runnable.class));
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 4, 4)).thenReturn(true);
            when(world.getName()).thenReturn("world");
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            var border = mock(WorldBorder.class);
            when(world.getWorldBorder()).thenReturn(border);
            when(border.isInside(any(Location.class))).thenReturn(true);
            when(world.isChunkLoaded(4, 4)).thenReturn(true);
            when(world.getChunkAtAsync(4, 4, false)).thenAnswer(call -> {
                assertEquals("region", owner);
                return chunk;
            });
            var floor = block(Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
            var air = block(Material.AIR);
            when(world.getBlockAt(eq(64), anyInt(), eq(64))).thenAnswer(call -> {
                assertEquals("region", owner);
                return call.<Integer>getArgument(1) == 63 ? floor : air;
            });
            when(player.teleportAsync(any(Location.class))).thenAnswer(call -> {
                assertEquals("entity", owner);
                return nativeTeleport;
            });
            when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
            when(repository.close()).thenReturn(CompletableFuture.completedFuture(null));
            var home = new Home(playerId, "base", "world", worldId, 64.5, 64, 64.5, 0, 0, 1);
            when(repository.findByName(playerId, "base")).thenReturn(CompletableFuture.completedFuture(Optional.of(home)));
            service = new DefaultHomeService(plugin, repository, () -> config, new ConcurrentTeleportPipelineEngine(plugin, 4), bankLane);
        }

        CompletableFuture<HomeResultStatus> begin() { return service.teleport(player, playerId, "base", USE, false); }
        void entityStep() { run(entityTasks.remove(), "entity"); }
        void regionStep() { run(regionTasks.remove(), "region"); }
        private void run(Runnable task, String context) {
            owner = context;
            try { task.run(); } finally { owner = "command"; }
        }
        @Override public void close() { service.close().join(); bankLane.close(); bukkit.close(); }
    }
}
