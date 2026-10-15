package com.lunatech.tpcore.config.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record WarpSignConfig(boolean enabled, int selectionTimeoutSeconds, int clickCooldownMillis,
    boolean protectBreaks, List<String> lines, Map<String, String> messages) {
    public WarpSignConfig {
        selectionTimeoutSeconds = Math.max(1, Math.min(300, selectionTimeoutSeconds));
        clickCooldownMillis = Math.max(0, Math.min(60000, clickCooldownMillis));
        if (lines == null) lines = defaultLines();
        if (lines.size() != 4 || lines.stream().anyMatch(line -> line == null || line.length() > 2048)) {
            throw new IllegalArgumentException("Warp signs require exactly four bounded text lines");
        }
        lines = List.copyOf(lines);
        Map<String, String> merged = new HashMap<>(defaultMessages());
        if (messages != null) merged.putAll(messages);
        messages = Map.copyOf(merged);
    }

    private static List<String> defaultLines() {
        return List.of("<aqua>[Warp]</aqua>", "<gold><warp></gold>", "<gray>Right-click</gray>", "<dark_gray>TPCore</dark_gray>");
    }

    public static Map<String, String> defaultMessages() {
        return Map.ofEntries(
            Map.entry("usage", "<prefix><gray>/warpsign create <name> | remove | info | cancel. Then right-click the desired sign face with your main hand.</gray>"),
            Map.entry("selected", "<prefix><yellow>Right-click a sign face within <seconds> seconds to <action>. /warpsign cancel stops selection.</yellow>"),
            Map.entry("create-action", "bind it to <warp>"),
            Map.entry("remove-action", "remove its warp binding and clear that face"),
            Map.entry("info-action", "inspect its warp binding"),
            Map.entry("cancelled", "<prefix><gray>Warp sign selection cancelled.</gray>"),
            Map.entry("expired", "<prefix><yellow>Warp sign selection expired. Run the command again.</yellow>"),
            Map.entry("created", "<prefix><green>Sign face bound to <warp>. Current warp access and prices apply on every click.</green>"),
            Map.entry("removed", "<prefix><green>Warp sign binding removed; that face is now blank.</green>"),
            Map.entry("info", "<prefix><gray>Bound warp: <gold><warp></gold>. Binding current: <valid>. Renamed or replaced warps need a new binding.</gray>"),
            Map.entry("not-sign", "<prefix><yellow>Right-click an existing sign, including a hanging sign.</yellow>"),
            Map.entry("not-bound", "<prefix><yellow>This sign face has no TPCore warp binding.</yellow>"),
            Map.entry("already-bound", "<prefix><yellow>Remove this face's existing binding with /warpsign remove before creating another.</yellow>"),
            Map.entry("stale", "<prefix><red>This sign binding is invalid, moved, or refers to a renamed/replaced warp. Ask an administrator to remove and recreate it.</red>"),
            Map.entry("failed", "<prefix><red>The sign could not be updated. Please try again.</red>"),
            Map.entry("no-permission", "<prefix><red>You do not have permission for this warp sign action.</red>"),
            Map.entry("disabled", "<prefix><yellow>Warp signs are currently disabled.</yellow>"),
            Map.entry("busy", "<prefix><yellow>Warp sign selection capacity is full. Please try again shortly.</yellow>"),
            Map.entry("edit-blocked", "<prefix><yellow>Remove this face's warp binding with /warpsign remove before editing its text.</yellow>"));
    }

    public static WarpSignConfig createDefault() {
        return new WarpSignConfig(true, 30, 750, true, defaultLines(), defaultMessages());
    }
}
