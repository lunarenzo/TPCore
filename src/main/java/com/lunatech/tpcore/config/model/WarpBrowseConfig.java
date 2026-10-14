package com.lunatech.tpcore.config.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record WarpBrowseConfig(boolean inventoryEnabled, int inventoryRows, int maxSearchLength,
    int maxDescriptionLength, int maxPresentationEntries, String defaultIcon, String categoryIcon,
    String previousIcon, String nextIcon, String refreshIcon, String closeIcon,
    Map<String, Presentation> presentations, Messages messages) {
    public WarpBrowseConfig {
        inventoryRows = Math.max(2, Math.min(6, inventoryRows));
        maxSearchLength = Math.max(1, Math.min(128, maxSearchLength));
        maxDescriptionLength = Math.max(1, Math.min(4096, maxDescriptionLength));
        maxPresentationEntries = Math.max(1, Math.min(4096, maxPresentationEntries));
        if (presentations == null) presentations = Map.of();
        if (presentations.size() > maxPresentationEntries) throw new IllegalArgumentException("Too many warp browsing presentations");
        Map<String, Presentation> normalized = new LinkedHashMap<>();
        for (var entry : presentations.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_-]{1,32}") || entry.getValue() == null) throw new IllegalArgumentException("Invalid warp presentation key");
            if (entry.getValue().description().length() > maxDescriptionLength) throw new IllegalArgumentException("Warp description exceeds configured length");
            if (normalized.put(name, entry.getValue()) != null) throw new IllegalArgumentException("Duplicate warp presentation key");
        }
        presentations = Map.copyOf(normalized);
        if (messages == null) messages = Messages.createDefault();
    }

    public static WarpBrowseConfig createDefault() {
        return new WarpBrowseConfig(false, 6, 32, 512, 512, "BOOK", "CHEST", "ARROW", "ARROW", "SUNFLOWER", "BARRIER", Map.of(), Messages.createDefault());
    }

    @ConfigSerializable
    public record Presentation(String icon, String description) {
        public Presentation { description = description == null ? "" : description; }
    }

    @ConfigSerializable
    public record Messages(String usage, String invalidSearch, String searchHeader, String previous, String next,
        String navigation, String categoriesHeader, String categoryItem, String inventoryDisabled, String inventoryTitle,
        String itemName, List<String> itemLore, String categoryTitle, String categoryName, List<String> categoryLore,
        String refresh, String close, String allCategories, String categoriesButton, String searchButton, String commandDescription) {
        public Messages {
            itemLore = itemLore == null ? List.of() : List.copyOf(itemLore);
            categoryLore = categoryLore == null ? List.of() : List.copyOf(categoryLore);
            if (itemLore.size() > 32 || categoryLore.size() > 32) throw new IllegalArgumentException("Too many warp browser lore lines");
        }
        public static Messages createDefault() {
            return new Messages(
                "<prefix><yellow>/warps [category|page] [page]; /warps search <term> [page]; /warps category <category> [search <term>] [page]; /warps categories; /warps gui [search <term> | category <category>] [page]</yellow>",
                "<prefix><red>Search must contain 1–<limit> characters.</red>",
                "<prefix><gold>Warps matching '<search>' in <category> (page <page>/<maxpages>):</gold>",
                "<yellow>[Previous]</yellow>", "<yellow>[Next]</yellow>",
                "<prefix><gray>Page <page>/<maxpages> — <total> results.</gray>",
                "<prefix><gold>Warp categories:</gold>", "<yellow>[<category>]</yellow>",
                "<prefix><yellow>The inventory warp browser is disabled.</yellow>",
                "<gold>Warps: <category> (<page>/<maxpages>)</gold>", "<yellow><name></yellow> <red><availability></red>",
                List.of("<gray><description></gray>", "<gray>Category: <category></gray>", "<gray>World: <world></gray>", "<red><reason></red>", "<gray>Click to request teleport; passwords use /warp <name> <password>.</gray>"),
                "<gold>Warp categories (<page>/<maxpages>)</gold>", "<yellow><category></yellow>", List.of("<gray>Click to browse this category.</gray>"),
                "<green>[Refresh]</green>", "<red>[Close]</red>", "All", "<yellow>[Categories]</yellow>", "<yellow>[Search]</yellow>", "Browse server warps"
            );
        }
    }
}
