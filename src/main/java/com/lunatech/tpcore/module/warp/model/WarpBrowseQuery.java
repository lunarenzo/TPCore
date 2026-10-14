package com.lunatech.tpcore.module.warp.model;

import java.util.Locale;

public record WarpBrowseQuery(String category, String search, int page, boolean categories) {
    public WarpBrowseQuery {
        category = category == null ? "" : category.toLowerCase(Locale.ROOT);
        search = search == null ? "" : search.toLowerCase(Locale.ROOT);
        page = Math.max(1, page);
    }
    public WarpBrowseQuery withPage(int value) { return new WarpBrowseQuery(category, search, value, categories); }
}
