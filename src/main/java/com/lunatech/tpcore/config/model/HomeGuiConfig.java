package com.lunatech.tpcore.config.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record HomeGuiConfig(boolean enabled, int promptTimeoutSeconds, Map<String, String> titles,
    Map<String, Icon> icons, Map<String, String> messages, Bedrock bedrock) {

    private static final Map<String, String> TITLES = Map.ofEntries(
        Map.entry("menu", "SetHome GUI – Menu"),
        Map.entry("homes", "My Homes (<page>/<pages>)"),
        Map.entry("confirm", "Delete home?")
    );
    private static final Map<String, Icon> ICONS = Map.ofEntries(
        Map.entry("frame", new Icon("GRAY_STAINED_GLASS_PANE", " ", List.of())),
        Map.entry("set", new Icon("RED_BED", "<gold>Set Home", List.of("<gray>Create a new home here.", "", "<gold>• Current homes: <white><count>/<limit>", "", "<yellow>▶ Click to enter a name."))),
        Map.entry("homes", new Icon("BOOK", "<gold>My Homes", List.of("<gray>View and teleport to your homes.", "", "<yellow>▶ Click to open the list."))),
        Map.entry("home", new Icon("CYAN_BED", "<gold>Home: <name>", List.of("<gray>Destination information:", "<gray>• World: <white><world>", "<gray>• X: <white><x> <gray>Y: <white><y> <gray>Z: <white><z>", ""))),
        Map.entry("previous", new Icon("PLAYER_HEAD", "<gold>◀ Previous Page", List.of())),
        Map.entry("next", new Icon("PLAYER_HEAD", "<gold>Next Page ▶", List.of())),
        Map.entry("back", new Icon("BARRIER", "<red>✖ Go Back", List.of())),
        Map.entry("confirm", new Icon("LIME_DYE", "<green>Confirm deletion", List.of("<gray>Archive <yellow><name><gray>. You can recover it with /homerestore."))),
        Map.entry("cancel", new Icon("RED_DYE", "<red>Cancel", List.of())),
        Map.entry("empty", new Icon("PAPER", "<yellow>No homes yet", List.of("<gray>Go back to create a home.")))
    );
    private static final Map<String, String> MESSAGES = Map.ofEntries(
        Map.entry("unlimited", "Unlimited"),
        Map.entry("left-click", "<gold>▶ Left-click to teleport."),
        Map.entry("right-click", "<red>◀ Right-click to delete."),
        Map.entry("prompt", "<yellow>Choose a name for your new home."),
        Map.entry("manual-name", "<gray>You can also type <yellow>/sethome <unused-name></yellow> using a name you haven't saved."),
        Map.entry("enter-name", "<green>[Enter a name]"),
        Map.entry("use-default", "<gold>[Use default: <name>]"),
        Map.entry("cancel-prompt", "<red>[Cancel]"),
        Map.entry("prompt-cancelled", "<gray>Home naming cancelled."),
        Map.entry("prompt-expired", "<yellow>This naming prompt expired. Open Set Home again."),
        Map.entry("disabled", "<yellow>The Home GUI is disabled."),
        Map.entry("home-changed", "<yellow>That home was changed or removed. Open the list again to see the latest destination.")
    );

    public HomeGuiConfig {
        promptTimeoutSeconds = Math.clamp(promptTimeoutSeconds, 1, 300);
        titles = merge(TITLES, titles);
        messages = merge(MESSAGES, messages);
        if (bedrock == null) bedrock = Bedrock.createDefault();
        Map<String, Icon> resolved = new HashMap<>(ICONS);
        if (icons != null) {
            for (var entry : ICONS.entrySet()) {
                String key = entry.getKey();
                Icon fallback = entry.getValue();
                Icon configured = icons.get(key);
                if (configured != null) resolved.put(key, new Icon(
                    configured.material() == null ? fallback.material() : configured.material(),
                    configured.name() == null ? fallback.name() : configured.name(),
                    configured.lore() == null ? fallback.lore() : configured.lore()));
            }
        }
        icons = Map.copyOf(resolved);
    }

    private static Map<String, String> merge(Map<String, String> defaults, Map<String, String> overrides) {
        Map<String, String> merged = new HashMap<>(defaults);
        if (overrides != null) merged.putAll(overrides);
        return Map.copyOf(merged);
    }

    public static HomeGuiConfig createDefault() {
        return new HomeGuiConfig(true, 60, TITLES, ICONS, MESSAGES, Bedrock.createDefault());
    }

    public static String defaultMaterial(String icon) {
        return ICONS.get(icon).material();
    }

    @ConfigSerializable
    public record Bedrock(boolean enabled, Map<String, String> messages) {
        private static final Map<String, String> DEFAULTS = Map.of(
            "title", "Create Home",
            "description", "Your home will be saved at your location when you submit.",
            "name-label", "Home name",
            "placeholder", "Enter a home name",
            "blank", "Enter a name for your home.",
            "invalid", "Use 1–32 letters, numbers, dots, underscores, plus signs, or hyphens.",
            "duplicate", "You already have a home with that name. Choose a different name.",
            "unavailable", "<yellow>The Bedrock form is unavailable."
        );

        public Bedrock {
            messages = merge(DEFAULTS, messages);
        }

        public static Bedrock createDefault() {
            return new Bedrock(true, DEFAULTS);
        }

        public String message(String key) {
            return messages.get(key);
        }
    }

    @ConfigSerializable
    public record Icon(String material, String name, List<String> lore) {
        public Icon {
            if (lore != null) lore = List.copyOf(lore);
        }
    }
}
