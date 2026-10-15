package com.lunatech.tpcore.config.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/** Immutable, bounded policies. An absent override inherits; zero is an explicit free/instant value. */
@ConfigSerializable
public record WarpPolicyConfig(Map<String, Override> warps, Map<String, Override> categories,
    WorldRule creationWorlds, WorldRule sourceWorlds, WorldRule destinationWorlds,
    boolean combatEnabled, int combatSeconds, boolean combatPvpOnly,
    boolean worldguardEnabled, boolean griefpreventionEnabled, Messages messages) {

    public WarpPolicyConfig {
        warps = normalize(warps);
        categories = normalize(categories);
        if (creationWorlds == null) creationWorlds = WorldRule.unrestricted();
        if (sourceWorlds == null) sourceWorlds = WorldRule.unrestricted();
        if (destinationWorlds == null) destinationWorlds = WorldRule.unrestricted();
        combatSeconds = Math.max(1, Math.min(300, combatSeconds));
        if (messages == null) messages = Messages.createDefault();
    }

    private static Map<String, Override> normalize(Map<String, Override> values) {
        if (values == null) return Map.of();
        if (values.size() > 4096) throw new IllegalArgumentException("At most 4096 warp/category overrides are allowed");
        Map<String, Override> result = new HashMap<>();
        values.forEach((key, value) -> {
            String name = key.toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_-]{1,32}") || value == null || result.putIfAbsent(name, value) != null)
                throw new IllegalArgumentException("Invalid or duplicate warp policy key: " + key);
        });
        return Map.copyOf(result);
    }

    public Effective resolve(String name, String category, WarpConfig global) {
        Override warp = warps.getOrDefault(name.toLowerCase(Locale.ROOT), Override.inherit());
        Override group = categories.getOrDefault(category.toLowerCase(Locale.ROOT), Override.inherit());
        return new Effective(first(warp.price(), group.price(), global.warpCost()),
            first(warp.warmupSeconds(), group.warmupSeconds(), global.warmupSeconds()),
            first(warp.cooldownSeconds(), group.cooldownSeconds(), global.cooldownSeconds()));
    }

    private static <T> T first(T warp, T category, T global) { return warp != null ? warp : category != null ? category : global; }

    public record Effective(double price, int warmupSeconds, int cooldownSeconds) {}

    @ConfigSerializable
    public record Override(Double price, Integer warmupSeconds, Integer cooldownSeconds) {
        private static final Override INHERIT = new Override(null, null, null);
        public Override {
            if (price != null && (!Double.isFinite(price) || price < 0)) throw new IllegalArgumentException("Override price must be finite and nonnegative");
            if (warmupSeconds != null && (warmupSeconds < 0 || warmupSeconds > 300)) throw new IllegalArgumentException("Override warmup must be 0..300 seconds");
            if (cooldownSeconds != null && (cooldownSeconds < 0 || cooldownSeconds > 86400)) throw new IllegalArgumentException("Override cooldown must be 0..86400 seconds");
        }
        public static Override inherit() { return INHERIT; }
    }

    @ConfigSerializable
    public record WorldRule(Set<String> allowed, Set<String> denied) {
        public WorldRule { allowed = names(allowed); denied = names(denied); }
        private static Set<String> names(Set<String> values) {
            if (values == null) return Set.of();
            if (values.size() > 256) throw new IllegalArgumentException("At most 256 worlds per rule are allowed");
            if (values.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 256))
                throw new IllegalArgumentException("World rule entries must contain 1..256 characters");
            return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }
        public boolean allows(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            return !denied.contains(key) && (allowed.isEmpty() || allowed.contains(key));
        }
        public boolean unrestrictedRule() { return allowed.isEmpty() && denied.isEmpty(); }
        public static WorldRule unrestricted() { return new WorldRule(Set.of(), Set.of()); }
    }

    @ConfigSerializable
    public record Messages(String quote, String worldRestricted, String combatTagged, String protectionDenied) {
        public static Messages createDefault() {
            return new Messages("<prefix><yellow>Warp <warp>: price <price>; warmup <warmup>s; cooldown <cooldown>s.</yellow>",
                "<prefix><red>Warp creation or travel is not allowed by the world rules.</red>",
                "<prefix><red>You cannot warp while combat tagged. Wait until combat expires.</red>",
                "<prefix><red>Protection denied this operation, or an enabled protection hook is unavailable or unsupported. Ask an administrator.</red>");
        }
    }

    public static WarpPolicyConfig createDefault() {
        return new WarpPolicyConfig(Map.of(), Map.of(), WorldRule.unrestricted(), WorldRule.unrestricted(), WorldRule.unrestricted(),
            false, 10, true, false, false, Messages.createDefault());
    }
}
