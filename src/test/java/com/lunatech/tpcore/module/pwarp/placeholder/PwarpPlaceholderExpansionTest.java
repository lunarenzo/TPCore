package com.lunatech.tpcore.module.pwarp.placeholder;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class PwarpPlaceholderExpansionTest {

    @Test
    @DisplayName("Verify PwarpPlaceholderExpansion resolution for total, visits, rating, and owner params")
    void testPlaceholderResolution() {
        PwarpService service = Mockito.mock(PwarpService.class);
        UUID owner = UUID.randomUUID();
        Pwarp warp = new Pwarp(1, owner, "Alex", "shop", "Shop warp", "world", 10, 64, 10, 0, 0, "CHEST", "shops", false, System.currentTimeMillis(), 42, 4.8, 10, 5.0, 100.0);

        Mockito.when(service.getPublicWarps()).thenReturn(List.of(warp));
        Mockito.when(service.getWarp("shop")).thenReturn(Optional.of(warp));

        PwarpPlaceholderExpansion expansion = new PwarpPlaceholderExpansion(service);

        Assertions.assertEquals("tpcore_pwarp", expansion.getIdentifier());
        Assertions.assertEquals("1", expansion.onRequest(null, "total"));
        Assertions.assertEquals("42", expansion.onRequest(null, "visits_shop"));
        Assertions.assertEquals("4.8", expansion.onRequest(null, "rating_shop"));
        Assertions.assertEquals("5.00", expansion.onRequest(null, "price_shop"));
        Assertions.assertEquals("Alex", expansion.onRequest(null, "owner_shop"));
        Assertions.assertEquals("shops", expansion.onRequest(null, "category_shop"));
    }
}
