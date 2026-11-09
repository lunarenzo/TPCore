package com.lunatech.tpcore.config.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record HomeRuntimeConfig(
    int storageQueueCapacity, int maxRequests, int maxCooldowns, int timeoutSeconds,
    int safetyFreshnessMillis, int listPageSize, boolean allowLegacyWorldNames, boolean generateChunks,
    int recoveryCapacity, int recoveryDays, Set<String> hazards, Map<String, String> messages,
    int stalledWorkReserve, int storageDrainSeconds
) {
    private static final Set<String> DEFAULT_HAZARDS = Set.of("MAGMA_BLOCK", "LAVA", "FIRE", "SOUL_FIRE", "CACTUS",
        "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE", "CAMPFIRE", "SOUL_CAMPFIRE", "POINTED_DRIPSTONE");
    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
        Map.entry("not-ready", "<prefix><yellow>Home storage is starting. Try again shortly.</yellow>"),
        Map.entry("storage-error", "<prefix><red>Home storage is unavailable. Contact an administrator.</red>"),
        Map.entry("busy", "<prefix><yellow>Home is busy or you already have a request. Try again shortly.</yellow>"),
        Map.entry("invalid-name", "<prefix><red>Use 1–32 letters, numbers, dots, underscores, plus signs or hyphens for a home name.</red>"),
        Map.entry("cancelled", "<prefix><yellow>Your home teleport was cancelled.</yellow>"),
        Map.entry("timed-out", "<prefix><yellow>Your home request timed out before teleporting. Try again shortly.</yellow>"),
        Map.entry("outcome-unknown", "<prefix><yellow>The server has not confirmed your teleport. Wait before trying again.</yellow>"),
        Map.entry("teleport-failed", "<prefix><red>The server did not complete your home teleport.</red>"),
        Map.entry("saved-unsafe", "<prefix><yellow>Home <home> was saved, but its landing position currently fails the safety check.</yellow>"),
        Map.entry("world-unavailable", "<prefix><red>The saved world is unavailable. Check with staff or reset this home in the intended world.</red>"),
        Map.entry("travel-world-restricted", "<prefix><red>This home's destination world is restricted.</red>"),
        Map.entry("no-permission", "<prefix><red>You no longer have permission to use this home command.</red>"),
        Map.entry("unknown-owner", "<prefix><red>Player <target> is not online or known by the server.</red>"),
        Map.entry("archived", "<prefix><green>Home <home> deleted. Use /homerestore <home> while its archive is available.</green>"),
        Map.entry("restored", "<prefix><green>Home <home> restored.</green>"),
        Map.entry("restore-conflict", "<prefix><red>You already have a home called <home>. The archive was kept.</red>"),
        Map.entry("recovery-not-found", "<prefix><red>No recoverable archive for <home> was found.</red>"),
        Map.entry("list-page", "<prefix><gold>Your homes — page <page></gold>"),
        Map.entry("empty-page", "<prefix><yellow>There are no homes on this page.</yellow>"),
        Map.entry("list-next", "<prefix><gray>Next page: <click:run_command:'/homes <page>'><gold>[Next]</gold></click></gray>"),
        Map.entry("benchmark-read", "<prefix><gray>Home storage read: <count> rows in <millis> ms (including queue time).</gray>")
    );

    public HomeRuntimeConfig {
        storageQueueCapacity = Math.clamp(storageQueueCapacity, 1, 512);
        maxRequests = Math.clamp(maxRequests, 1, 256);
        maxCooldowns = Math.clamp(maxCooldowns, maxRequests, 32768);
        timeoutSeconds = Math.clamp(timeoutSeconds, 1, 120);
        safetyFreshnessMillis = Math.clamp(safetyFreshnessMillis, 50, 5000);
        listPageSize = Math.clamp(listPageSize, 1, 50);
        recoveryCapacity = Math.clamp(recoveryCapacity, 1, 100000);
        recoveryDays = Math.clamp(recoveryDays, 1, 365);
        stalledWorkReserve = Math.clamp(stalledWorkReserve, 0, 16);
        storageDrainSeconds = Math.clamp(storageDrainSeconds, 1, 120);
        hazards = hazards == null ? DEFAULT_HAZARDS : hazards.stream().map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        Map<String, String> merged = new HashMap<>(DEFAULT_MESSAGES);
        if (messages != null) merged.putAll(messages);
        messages = Map.copyOf(merged);
    }

    public static HomeRuntimeConfig createDefault() {
        return new HomeRuntimeConfig(64, 32, 2048, 15, 500, 10, true, false, 2048, 7, DEFAULT_HAZARDS, DEFAULT_MESSAGES, 2, 10);
    }

    public String message(String key) { return messages.get(key); }
}
