package com.lunatech.tpcore.module.warp.model;

import java.util.List;

public record WarpBrowsePage(WarpBrowseQuery query, List<Warp> warps, List<String> categories, int pages, int total) {
    public WarpBrowsePage { warps = List.copyOf(warps); categories = List.copyOf(categories); }
}
