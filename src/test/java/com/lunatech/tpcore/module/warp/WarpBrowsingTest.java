package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpBrowseConfig;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpBrowseQuery;
import com.lunatech.tpcore.module.warp.util.WarpBrowsing;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarpBrowsingTest {
    private Warp warp(String name, String category) { return new Warp(name, null, "world", 0, 64, 0, 0, 0, null, category, null, false, 0); }

    @Test void orderingIsAlphabeticalBeforePaginationRegardlessOfInsertionOrder() {
        var source = List.of(warp("zoo", "other"), warp("Beta", "general"), warp("alpha", "general"));
        var first = WarpBrowsing.page(source, warp -> true, new WarpBrowseQuery(null, null, 1, false), 2);
        var last = WarpBrowsing.page(source.reversed(), warp -> true, new WarpBrowseQuery(null, null, 2, false), 2);
        assertEquals(List.of("alpha", "Beta"), first.warps().stream().map(Warp::name).toList());
        assertEquals(List.of("zoo"), last.warps().stream().map(Warp::name).toList()); assertEquals(2, last.pages());
    }

    @Test void nameSearchAndCategoryAreIndependentCaseInsensitiveFilters() {
        var page = WarpBrowsing.page(List.of(warp("HubWest", "market"), warp("HubEast", "general"), warp("Store", "market")), warp -> true,
            new WarpBrowseQuery("MARKET", "HUB", 1, false), 8);
        assertEquals(List.of("HubWest"), page.warps().stream().map(Warp::name).toList());
        assertEquals(List.of("general", "market"), page.categories());
    }

    @Test void deniedWarpsNeverInfluenceCategoriesOrPageCounts() {
        var page = WarpBrowsing.page(List.of(warp("secret", "hidden"), warp("hub", "general")), warp -> !warp.name().equals("secret"), new WarpBrowseQuery(null, null, Integer.MAX_VALUE, false), Integer.MAX_VALUE);
        assertEquals(List.of("general"), page.categories()); assertEquals(1, page.total()); assertEquals(1, page.query().page());
    }

    @Test void categoryPagesAndEmptyResultsClampWithoutOverflow() {
        var source = List.of(warp("a", "first"), warp("b", "second"), warp("c", "third"));
        var categories = WarpBrowsing.page(source, warp -> true, new WarpBrowseQuery(null, null, 9, true), 2);
        assertEquals(2, categories.pages()); assertEquals(2, categories.query().page()); assertEquals(3, categories.total());
        var empty = WarpBrowsing.page(source, warp -> true, new WarpBrowseQuery(null, "missing", 9, false), 0);
        assertEquals(0, empty.total()); assertEquals(1, empty.pages()); assertTrue(empty.warps().isEmpty());
    }

    @Test void presentationSettingsAreCaseNormalizedImmutableAndBounded() {
        var defaults = WarpBrowseConfig.createDefault();
        var configured = new WarpBrowseConfig(true, 9, 32, 10, 1, "BOOK", "CHEST", "ARROW", "ARROW", "SUNFLOWER", "BARRIER", Map.of("Hub", new WarpBrowseConfig.Presentation("COMPASS", "Arrival")), defaults.messages());
        assertEquals(6, configured.inventoryRows()); assertTrue(configured.presentations().containsKey("hub"));
        assertThrows(UnsupportedOperationException.class, () -> configured.presentations().clear());
        assertThrows(IllegalArgumentException.class, () -> new WarpBrowseConfig(true, 6, 32, 3, 1, "BOOK", "CHEST", "ARROW", "ARROW", "SUNFLOWER", "BARRIER", configured.presentations(), defaults.messages()));
    }
}
