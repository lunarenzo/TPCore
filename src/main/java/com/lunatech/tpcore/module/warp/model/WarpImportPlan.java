package com.lunatech.tpcore.module.warp.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WarpImportPlan(UUID token, String policy, Map<String, String> fingerprints,
    Map<String, Warp> destination, List<Warp> selected, List<Entry> entries, int conflicts, int invalid) {
    public WarpImportPlan {
        fingerprints = Map.copyOf(fingerprints); destination = Map.copyOf(destination);
        selected = List.copyOf(selected); entries = List.copyOf(entries);
    }
    public record Entry(String file, String name, String target, String reason) {}
    public record WorldData(UUID id, String name, int minHeight, int maxHeight) {}
    public record Result(int count, String backup) {}
}
