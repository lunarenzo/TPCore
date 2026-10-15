package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpConfig.WarpSafetyConfig;
import com.lunatech.tpcore.config.model.WarpPolicyConfig;
import com.lunatech.tpcore.config.model.WarpPolicyConfig.WorldRule;
import com.lunatech.tpcore.module.warp.service.impl.WarpSafetyInspector;
import com.lunatech.tpcore.module.warp.service.impl.WarpSafetyInspector.Policy;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarpPolicyTest {
    private WarpPolicyConfig policies(Map<String, WarpPolicyConfig.Override> warps, Map<String, WarpPolicyConfig.Override> categories) {
        var d = WarpPolicyConfig.createDefault();
        return new WarpPolicyConfig(warps, categories, d.creationWorlds(), d.sourceWorlds(), d.destinationWorlds(), false, 10, true, false, false, d.messages());
    }

    @Test void overridesInheritEachPropertyAndZeroDoesNotMeanInherit() {
        var config = policies(Map.of("HuB", new WarpPolicyConfig.Override(0.0, null, 0)), Map.of("GENERAL", new WarpPolicyConfig.Override(25.0, 7, 45)));
        var hub = config.resolve("hub", "general", WarpConfig.createDefault());
        assertEquals(0, hub.price()); assertEquals(7, hub.warmupSeconds()); assertEquals(0, hub.cooldownSeconds());
        assertEquals(25, config.resolve("other", "general", WarpConfig.createDefault()).price());
        assertEquals(3, config.resolve("other", "other", WarpConfig.createDefault()).warmupSeconds());
    }

    @Test void malformedOrAmbiguousOverridesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WarpPolicyConfig.Override(Double.NaN, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WarpPolicyConfig.Override(-1.0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WarpPolicyConfig.Override(null, 301, null));
        assertThrows(IllegalArgumentException.class, () -> policies(Map.of("Hub", WarpPolicyConfig.Override.inherit(), "hub", WarpPolicyConfig.Override.inherit()), Map.of()));
    }

    @Test void worldRulesAreCaseInsensitiveAndDenyTakesPrecedence() {
        var rule = new WorldRule(Set.of("WORLD", "event"), Set.of("EVENT"));
        assertTrue(rule.allows("world")); assertFalse(rule.allows("event")); assertFalse(rule.allows("nether"));
        assertTrue(WorldRule.unrestricted().allows("any"));
    }

    private Policy safety(String mode, int radius, String water, Set<String> hazards) {
        return new Policy(new WarpSafetyConfig(true, false, 128, mode, radius, 256, water, hazards));
    }

    @Test void searchIsDeterministicEuclideanBoundedAndExactHasOneCandidate() {
        Policy nearby = safety("NEARBY", 3, "ALLOW", Set.of());
        assertEquals(new WarpSafetyInspector.Offset(0, 0, 0), nearby.offsets().getFirst());
        assertEquals(123, nearby.offsets().size());
        int previous = -1;
        for (var offset : nearby.offsets()) {
            int distance = offset.x() * offset.x() + offset.y() * offset.y() + offset.z() * offset.z();
            assertTrue(distance <= 9 && distance >= previous); previous = distance;
        }
        assertEquals(1, safety("EXACT", 10, "ALLOW", Set.of()).offsets().size());
        assertEquals(256, safety("NEARBY", 10, "ALLOW", Set.of()).offsets().size());
    }

    private World world() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        return world;
    }
    private Block block(Material type, boolean passable) {
        Block block = mock(Block.class); when(block.getType()).thenReturn(type); when(block.isPassable()).thenReturn(passable); return block;
    }

    @Test void waterModesAndCustomHazardsHaveIndependentExplicitMeaning() {
        World world = world();
        Block water = block(Material.WATER, true), air = block(Material.AIR, true);
        when(world.getBlockAt(0, 63, 0)).thenReturn(water);
        when(world.getBlockAt(0, 64, 0)).thenReturn(air); when(world.getBlockAt(0, 65, 0)).thenReturn(air);
        Location target = new Location(world, .5, 64, .5);
        assertFalse(WarpSafetyInspector.isLocationSafe(target, safety("EXACT", 0, "DENY", Set.of())));
        assertTrue(WarpSafetyInspector.isLocationSafe(target, safety("EXACT", 0, "SURFACE", Set.of())));
        when(world.getBlockAt(0, 64, 0)).thenReturn(water);
        assertFalse(WarpSafetyInspector.isLocationSafe(target, safety("EXACT", 0, "SURFACE", Set.of())));
        assertTrue(WarpSafetyInspector.isLocationSafe(target, safety("EXACT", 0, "ALLOW", Set.of())));
        assertFalse(WarpSafetyInspector.isLocationSafe(target, safety("EXACT", 0, "ALLOW", Set.of("WATER"))));
    }

    @Test void nearbySearchSchedulesCandidateOwnerAcrossNegativeChunkBoundary() {
        World world = world();
        Plugin plugin = mock(Plugin.class);
        RegionScheduler scheduler = mock(RegionScheduler.class);
        AtomicInteger owner = new AtomicInteger(Integer.MAX_VALUE);
        when(world.getChunkAtAsync(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        Block ground = block(Material.BEDROCK, false), air = block(Material.AIR, true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            assertEquals(x >> 4, owner.get(), "Block read must run on its owning chunk");
            if (x == -1 && z == 0 && y == 63) return ground;
            return air;
        });
        doAnswer(call -> {
            Location at = call.getArgument(1); owner.set(at.getBlockX() >> 4);
            Runnable action = call.getArgument(2); action.run(); return null;
        }).when(scheduler).execute(eq(plugin), any(Location.class), any(Runnable.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(scheduler);
            Location target = new Location(world, .5, 64, .5, 50, 10);
            Location result = WarpSafetyInspector.findSafeLocationAsync(plugin, target, safety("NEARBY", 1, "ALLOW", Set.of()), () -> true, ignored -> {}).join();
            assertNotNull(result); assertEquals(-.5, result.getX()); assertEquals(64, result.getY()); assertEquals(50, result.getYaw());
            assertNull(WarpSafetyInspector.findSafeLocationAsync(plugin, target, safety("EXACT", 3, "ALLOW", Set.of()), () -> true, ignored -> {}).join());
        }
    }

    @Test void cancelledSearchDoesNotLoadChunks() {
        World world = world();
        assertNull(WarpSafetyInspector.findSafeLocationAsync(mock(Plugin.class), new Location(world, 0, 64, 0), safety("NEARBY", 3, "ALLOW", Set.of()), () -> false, ignored -> {}).join());
        verify(world, never()).getChunkAtAsync(anyInt(), anyInt());
    }

    @Test void exactFractionalPositionRejectsSideAndHeadObstructions() {
        World world = world();
        Block air = block(Material.AIR, true), solid = block(Material.BEDROCK, false);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(world.getBlockAt(0, 63, 0)).thenReturn(solid);
        when(world.getBlockAt(1, 65, 0)).thenReturn(solid);
        Policy policy = safety("EXACT", 0, "ALLOW", Set.of());
        assertTrue(WarpSafetyInspector.isLocationSafe(new Location(world, .5, 64, .5), policy));
        assertFalse(WarpSafetyInspector.isLocationSafe(new Location(world, .9, 64, .5), policy));
        when(world.getBlockAt(0, 66, 0)).thenReturn(solid);
        assertFalse(WarpSafetyInspector.isLocationSafe(new Location(world, .5, 64.5, .5), policy));
    }
}
