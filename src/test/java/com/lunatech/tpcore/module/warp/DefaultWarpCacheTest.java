package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.model.Warp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWarpCacheTest {

    private DefaultWarpCache cache;
    private final UUID worldId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cache = new DefaultWarpCache();
    }

    @Test
    @DisplayName("Put and get warp case-insensitively")
    void testPutAndGetCaseInsensitive() {
        Warp warp = new Warp("Spawn", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "general", null, false, 1000L);
        cache.putWarp(warp);

        assertEquals(1, cache.getWarpCount());
        Optional<Warp> retrieved = cache.getWarp("spawn");
        assertTrue(retrieved.isPresent());
        assertEquals("Spawn", retrieved.get().name());

        Optional<Warp> retrievedUpper = cache.getWarp("SPAWN");
        assertTrue(retrievedUpper.isPresent());
        assertEquals("Spawn", retrievedUpper.get().name());
    }

    @Test
    @DisplayName("Category indexing correctly updates when warp category changes")
    void testCategoryIndexingReassignment() {
        Warp w1 = new Warp("Shop", worldId, "world", 10, 64, 10, 0f, 0f, creatorId, "market", null, false, 1000L);
        cache.putWarp(w1);

        assertEquals(1, cache.getWarpsByCategory("market").size());
        assertTrue(cache.getCategories().contains("market"));

        // Update category from market to vip
        Warp w2 = new Warp("Shop", worldId, "world", 10, 64, 10, 0f, 0f, creatorId, "vip", null, false, 1000L);
        cache.putWarp(w2);

        assertEquals(0, cache.getWarpsByCategory("market").size());
        assertEquals(1, cache.getWarpsByCategory("vip").size());
        assertFalse(cache.getCategories().contains("market"));
        assertTrue(cache.getCategories().contains("vip"));
    }

    @Test
    @DisplayName("Remove warp removes from warpMap and category index")
    void testRemoveWarp() {
        Warp w1 = new Warp("PvP", worldId, "world", 100, 64, 100, 0f, 0f, creatorId, "combat", null, false, 1000L);
        cache.putWarp(w1);

        assertEquals(1, cache.getWarpCount());
        assertTrue(cache.getCategories().contains("combat"));

        cache.removeWarp("pvp");

        assertEquals(0, cache.getWarpCount());
        assertFalse(cache.getWarp("pvp").isPresent());
        assertEquals(0, cache.getWarpsByCategory("combat").size());
        assertFalse(cache.getCategories().contains("combat"));
    }

    @Test
    @DisplayName("Populate replaces existing cache contents")
    void testPopulate() {
        Warp oldWarp = new Warp("Old", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "oldcat", null, false, 1000L);
        cache.putWarp(oldWarp);

        Warp newWarp1 = new Warp("One", worldId, "world", 1, 64, 1, 0f, 0f, creatorId, "general", null, false, 2000L);
        Warp newWarp2 = new Warp("Two", worldId, "world", 2, 64, 2, 0f, 0f, creatorId, "general", null, false, 2000L);

        cache.populate(Map.of("one", newWarp1, "two", newWarp2));

        assertEquals(2, cache.getWarpCount());
        assertFalse(cache.getWarp("old").isPresent());
        assertTrue(cache.getWarp("one").isPresent());
        assertTrue(cache.getWarp("two").isPresent());
    }

    @Test
    @DisplayName("Clear removes all warps and category indexes")
    void testClear() {
        Warp w1 = new Warp("A", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "cat1", null, false, 1000L);
        Warp w2 = new Warp("B", worldId, "world", 0, 64, 0, 0f, 0f, creatorId, "cat2", null, false, 1000L);
        cache.putWarp(w1);
        cache.putWarp(w2);

        assertEquals(2, cache.getWarpCount());
        assertEquals(2, cache.getCategories().size());

        cache.clear();

        assertEquals(0, cache.getWarpCount());
        assertEquals(0, cache.getCategories().size());
        assertTrue(cache.getAllWarps().isEmpty());
    }
}
