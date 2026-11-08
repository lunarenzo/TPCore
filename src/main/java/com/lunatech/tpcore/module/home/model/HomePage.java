package com.lunatech.tpcore.module.home.model;

import java.util.List;

public record HomePage(long total, long page, List<Entry> entries) {
    public static final int SIZE = 28;

    public HomePage {
        entries = List.copyOf(entries);
    }

    public long pages() {
        return pageCount(total);
    }

    public static long pageCount(long total) {
        return total == 0 ? 1 : 1 + (total - 1) / SIZE;
    }

    public record Entry(long rowId, Home home) {}
}
