package com.lunatech.tpcore.module.pwarp;

import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.cache.impl.DefaultPwarpCache;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class DefaultPwarpCacheTest {

    @Test
    @DisplayName("Verify PwarpCache put, getByName, remove, owner filtering, and public indexing")
    void testCacheOperations() {
        PwarpCache cache = new DefaultPwarpCache();

        UUID owner1 = UUID.randomUUID();
        UUID owner2 = UUID.randomUUID();

        Pwarp shop = new Pwarp(1, owner1, "Alex", "shop", "My shop", "world", 100, 64, 200, 0, 0, "CHEST", false, System.currentTimeMillis(), 10);
        Pwarp farm = new Pwarp(2, owner1, "Alex", "farm", "Mob farm", "world", -50, 70, 300, 0, 0, "SPAWNER", false, System.currentTimeMillis(), 5);
        Pwarp secret = new Pwarp(3, owner2, "Bob", "secret", "Private base", "world_nether", 0, 100, 0, 0, 0, "OBSIDIAN", true, System.currentTimeMillis(), 1);

        cache.put(shop);
        cache.put(farm);
        cache.put(secret);

        Assertions.assertEquals(3, cache.size());

        // Case-insensitive lookup
        Optional<Pwarp> foundShop = cache.getByName("SHOP");
        Assertions.assertTrue(foundShop.isPresent());
        Assertions.assertEquals("shop", foundShop.get().name());

        // Owner filtering
        List<Pwarp> alexWarps = cache.getByOwner(owner1);
        Assertions.assertEquals(2, alexWarps.size());
        Assertions.assertEquals(2, cache.countByOwner(owner1));

        // Public warp filtering
        List<Pwarp> publicWarps = cache.getAllPublic();
        Assertions.assertEquals(2, publicWarps.size());
        Assertions.assertFalse(publicWarps.stream().anyMatch(Pwarp::isPrivate));

        // Removal
        cache.remove("farm");
        Assertions.assertEquals(2, cache.size());
        Assertions.assertTrue(cache.getByName("farm").isEmpty());

        // Clear
        cache.clear();
        Assertions.assertEquals(0, cache.size());
    }
}
