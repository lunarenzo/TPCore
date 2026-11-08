package com.lunatech.tpcore.module.home.gui;

import com.lunatech.tpcore.config.model.HomeGuiConfig;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.model.HomePage;
import com.lunatech.tpcore.util.MessageFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

final class HomeMenuRenderer {
    static final int SET = 11, LIST = 15, PREVIOUS = 45, BACK = 49, NEXT = 53;
    static final int CONFIRM = 11, CANCEL = 15;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private HomeMenuRenderer() {}

    static HomeMenuHolder main(UUID player, UUID token, HomeGuiConfig gui, long count, int limit, boolean set, boolean list) {
        var holder = new HomeMenuHolder(player, token, HomeMenuHolder.Screen.MENU, 1, 1, List.of(), null, title(gui, "menu"));
        frame(holder, gui);
        var inventory = holder.getInventory();
        if (set) inventory.setItem(SET, icon(gui, "set", List.of(),
            Placeholder.unparsed("count", Long.toString(count)), Placeholder.unparsed("limit",
                limit == Integer.MAX_VALUE ? gui.messages().get("unlimited") : Integer.toString(limit))));
        if (list) inventory.setItem(LIST, icon(gui, "homes", List.of()));
        return holder;
    }

    static HomeMenuHolder homes(UUID player, UUID token, HomeGuiConfig gui, HomePage page, boolean use, boolean delete) {
        var holder = new HomeMenuHolder(player, token, HomeMenuHolder.Screen.HOMES, page.page(), page.pages(), page.entries(), null,
            title(gui, "homes", Placeholder.unparsed("page", Long.toString(page.page())),
                Placeholder.unparsed("pages", Long.toString(page.pages()))));
        frame(holder, gui);
        var inventory = holder.getInventory();
        if (page.page() > 1) inventory.setItem(PREVIOUS, icon(gui, "previous", List.of()));
        inventory.setItem(BACK, icon(gui, "back", List.of()));
        if (page.page() < page.pages()) inventory.setItem(NEXT, icon(gui, "next", List.of()));
        if (page.entries().isEmpty()) inventory.setItem(22, icon(gui, "empty", List.of()));
        List<String> actions = new ArrayList<>(2);
        if (use) actions.add(gui.messages().get("left-click"));
        if (delete) actions.add(gui.messages().get("right-click"));
        for (int index = 0; index < page.entries().size(); index++) {
            Home home = page.entries().get(index).home();
            inventory.setItem(slot(index), icon(gui, "home", actions, homeTags(home)));
        }
        return holder;
    }

    static HomeMenuHolder confirm(UUID player, UUID token, HomeGuiConfig gui, long page, HomePage.Entry entry) {
        TagResolver[] tags = homeTags(entry.home());
        var holder = new HomeMenuHolder(player, token, HomeMenuHolder.Screen.CONFIRM, page, 1, List.of(), entry,
            title(gui, "confirm", tags));
        frame(holder, gui);
        var inventory = holder.getInventory();
        inventory.setItem(13, icon(gui, "home", List.of(), tags));
        inventory.setItem(CONFIRM, icon(gui, "confirm", List.of(), tags));
        inventory.setItem(CANCEL, icon(gui, "cancel", List.of(), tags));
        return holder;
    }

    static int slot(int index) { return 10 + (index / 7) * 9 + index % 7; }

    static int index(int slot) {
        int row = slot / 9, column = slot % 9;
        return row >= 1 && row <= 4 && column >= 1 && column <= 7 ? (row - 1) * 7 + column - 1 : -1;
    }

    private static void frame(HomeMenuHolder holder, HomeGuiConfig gui) {
        var inventory = holder.getInventory();
        ItemStack frame = icon(gui, "frame", List.of());
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            if (slot < 9 || slot >= size - 9 || slot % 9 == 0 || slot % 9 == 8) inventory.setItem(slot, frame);
        }
    }

    private static ItemStack icon(HomeGuiConfig gui, String key, List<String> extra, TagResolver... tags) {
        var style = gui.icons().get(key);
        Material fallback = Material.matchMaterial(HomeGuiConfig.defaultMaterial(key));
        Material material = Material.matchMaterial(style.material());
        if (material == null || (material != fallback && (material.isAir() || !material.isItem()))) material = fallback;
        var stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.itemName(MessageFormatter.inventoryText(MessageFormatter.parse(MINI, style.name(), tags)));
            List<Component> lore = new ArrayList<>(style.lore().size() + extra.size());
            for (String line : style.lore()) lore.add(MessageFormatter.inventoryText(MessageFormatter.parse(MINI, line, tags)));
            for (String line : extra) lore.add(MessageFormatter.inventoryText(MessageFormatter.parse(MINI, line, tags)));
            if (!lore.isEmpty()) meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Component title(HomeGuiConfig gui, String key, TagResolver... tags) {
        return MessageFormatter.parse(MINI, gui.titles().get(key), tags);
    }

    private static TagResolver[] homeTags(Home home) {
        return new TagResolver[] {
            Placeholder.unparsed("name", home.name()), Placeholder.unparsed("world", home.worldName()),
            Placeholder.unparsed("x", String.format(Locale.ROOT, "%.2f", home.x())),
            Placeholder.unparsed("y", String.format(Locale.ROOT, "%.2f", home.y())),
            Placeholder.unparsed("z", String.format(Locale.ROOT, "%.2f", home.z()))
        };
    }
}
