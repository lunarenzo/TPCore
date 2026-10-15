package com.lunatech.tpcore.module.warp.gui;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpBrowsePage;
import com.lunatech.tpcore.module.warp.model.WarpBrowseQuery;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.module.warp.util.WarpBrowsing;
import com.lunatech.tpcore.util.MessageFormatter;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class WarpBrowser {
    private final JavaPlugin plugin;
    private final Supplier<WarpService> services;
    private final Supplier<WarpConfig> configs;
    private final BiConsumer<Player, String> teleport;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Map<UUID, Long>> opened = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<UUID, Pending>> pending = new AtomicReference<>(Map.of());

    private static final class Pending {
        final AtomicReference<ScheduledTask> task = new AtomicReference<>();
        final AtomicBoolean done = new AtomicBoolean();
    }

    public WarpBrowser(JavaPlugin plugin, Supplier<WarpService> services, Supplier<WarpConfig> configs, BiConsumer<Player, String> teleport) {
        this.plugin = plugin; this.services = services; this.configs = configs; this.teleport = teleport;
    }

    /** Called only in the owning player's execution context. */
    public void open(Player player, WarpBrowseQuery query) {
        WarpConfig settings = configs.get(); WarpService service = services.get();
        if (!plugin.isEnabled() || !settings.enabled() || !settings.browsing().inventoryEnabled()) { message(player, settings.browsing().messages().inventoryDisabled(), settings); return; }
        if (!player.hasPermission(Permissions.WARP_LIST)) { message(player, settings.messages().noPermission(), settings); return; }
        if (service == null || !service.isReady()) { message(player, settings.messages().storageNotReady(), settings); return; }
        if (query.search().length() > settings.browsing().maxSearchLength()) { message(player, settings.browsing().messages().invalidSearch(), settings, Placeholder.unparsed("limit", Integer.toString(settings.browsing().maxSearchLength()))); return; }
        UUID owner = player.getUniqueId(); long token = sequence.incrementAndGet(), epoch = generation.get();
        if (!reserve(owner, token, settings.maxPlayerStateEntries())) { message(player, settings.messages().operationBusy(), settings); return; }
        try {
            int size = settings.browsing().inventoryRows() * 9, content = size - 9;
            WarpBrowsePage page = WarpBrowsing.page(service.getAllWarps(), warp -> WarpBrowsing.visible(player, warp), query, content);
            var messages = settings.browsing().messages();
            TagResolver[] info = {Placeholder.unparsed("category", query.category().isEmpty() ? messages.allCategories() : query.category()),
                Placeholder.unparsed("search", query.search()), Placeholder.unparsed("page", Integer.toString(page.query().page())), Placeholder.unparsed("maxpages", Integer.toString(page.pages()))};
            WarpBrowserHolder holder = new WarpBrowserHolder(owner, token, page.query(), size,
                text(query.categories() ? messages.categoryTitle() : messages.inventoryTitle(), settings, info));
            if (query.categories()) {
                int start = (page.query().page() - 1) * content;
                for (int i = start; i < Math.min(start + content, page.categories().size()); i++) {
                    String category = page.categories().get(i); int slot = i - start;
                    holder.entry(slot, category);
                    holder.getInventory().setItem(slot, item(settings.browsing().categoryIcon(), messages.categoryName(), messages.categoryLore(), settings, Placeholder.unparsed("category", category)));
                }
            } else {
                for (int slot = 0; slot < page.warps().size(); slot++) {
                    Warp warp = page.warps().get(slot);
                    var presentation = settings.browsing().presentations().get(warp.name().toLowerCase(Locale.ROOT));
                    String icon = presentation == null || presentation.icon() == null ? settings.browsing().defaultIcon() : presentation.icon();
                    holder.entry(slot, warp.name());
                    holder.getInventory().setItem(slot, item(icon, messages.itemName(), messages.itemLore(), settings,
                        Placeholder.unparsed("name", warp.name()), Placeholder.unparsed("category", warp.category()), Placeholder.unparsed("world", warp.worldName()),
                        Placeholder.unparsed("description", presentation == null ? "" : presentation.description()), Placeholder.unparsed("reason", warp.closureReason()),
                        Placeholder.unparsed("availability", warp.closed() ? settings.controlMessages().closedLabel() : settings.controlMessages().openLabel())));
                }
            }
            if (page.query().page() > 1) holder.getInventory().setItem(content, item(settings.browsing().previousIcon(), messages.previous(), List.of(), settings, info));
            if (page.query().page() < page.pages()) holder.getInventory().setItem(content + 8, item(settings.browsing().nextIcon(), messages.next(), List.of(), settings, info));
            holder.getInventory().setItem(content + 1, item(settings.browsing().defaultIcon(), messages.searchButton(), List.of(), settings));
            holder.getInventory().setItem(content + 3, item(settings.browsing().categoryIcon(), query.categories() ? messages.allCategories() : messages.categoriesButton(), List.of(), settings));
            holder.getInventory().setItem(content + 4, item(settings.browsing().refreshIcon(), messages.refresh(), List.of(), settings));
            holder.getInventory().setItem(content + 5, item(settings.browsing().closeIcon(), messages.close(), List.of(), settings));
            if (generation.get() != epoch || !Long.valueOf(token).equals(opened.get().get(owner)) || !plugin.isEnabled()
                || services.get() != service || !service.isReady() || configs.get() != settings) { forget(owner, token); return; }
            player.openInventory(holder.getInventory());
            if (!holder.getInventory().getViewers().contains(player)) forget(owner, token);
        } catch (RuntimeException failure) { forget(owner, token); message(player, settings.messages().operationFailed(), settings); }
    }

    private boolean reserve(UUID id, long token, int limit) {
        for (;;) {
            Map<UUID, Long> old = opened.get();
            if (!old.containsKey(id) && old.size() >= limit) return false;
            Map<UUID, Long> next = new HashMap<>(old); next.put(id, token);
            if (opened.compareAndSet(old, Map.copyOf(next))) return true;
        }
    }

    /** Cancel the event first; this method only queues one action per player for the next entity tick. */
    public void click(Player player, WarpBrowserHolder holder, int slot) {
        UUID id = player.getUniqueId();
        if (!id.equals(holder.owner())) return;
        defer(player, id, current -> {
            if (!holder.getInventory().getViewers().contains(current)) return;
            if (!Long.valueOf(holder.token()).equals(opened.get().get(id))) { current.closeInventory(); return; }
            interact(current, holder, slot);
        });
    }

    private boolean defer(Player player, UUID id, Consumer<Player> actionLogic) {
        Long viewToken = opened.get().get(id);
        Pending action = new Pending();
        for (;;) {
            Map<UUID, Pending> old = pending.get();
            if (old.containsKey(id) || old.size() >= configs.get().maxPlayerStateEntries()) return false;
            Map<UUID, Pending> next = new HashMap<>(old); next.put(id, action);
            if (pending.compareAndSet(old, Map.copyOf(next))) break;
        }
        long epoch = generation.get();
        try {
            ScheduledTask task = player.getScheduler().run(plugin, ignored -> {
                try {
                    Player current = Bukkit.getPlayer(id);
                    if (current == null || action.done.get() || generation.get() != epoch) return;
                    actionLogic.accept(current);
                } finally { finish(id, action); }
            }, () -> { if (viewToken != null) forget(id, viewToken); finish(id, action); });
            action.task.set(task);
            if (task == null) { if (viewToken != null) forget(id, viewToken); finish(id, action); }
            else if (action.done.get()) task.cancel();
            return task != null;
        } catch (RuntimeException failure) { if (viewToken != null) forget(id, viewToken); finish(id, action); return false; }
    }

    private void interact(Player player, WarpBrowserHolder holder, int slot) {
        WarpConfig settings = configs.get(); WarpService service = services.get();
        if (!settings.enabled() || !settings.browsing().inventoryEnabled() || !player.hasPermission(Permissions.WARP_LIST) || service == null || !service.isReady()) {
            player.closeInventory(); return;
        }
        int footer = holder.contentSize(); WarpBrowseQuery query = holder.query();
        if (slot == footer + 5) { player.closeInventory(); return; }
        if (slot == footer + 1) {
            player.closeInventory();
            String command = "/warps gui" + (query.category().isEmpty() ? "" : " category " + StringArgumentType.escapeIfRequired(query.category())) + " search ";
            player.sendMessage(text(settings.browsing().messages().searchButton(), settings).clickEvent(ClickEvent.suggestCommand(command))); return;
        }
        if (slot == footer) { open(player, query.withPage(Math.max(1, query.page() - 1))); return; }
        if (slot == footer + 8) { open(player, query.withPage(query.page() + 1)); return; }
        if (slot == footer + 3) { open(player, new WarpBrowseQuery(query.categories() ? "" : query.category(), query.search(), 1, !query.categories())); return; }
        if (slot == footer + 4) { open(player, query); return; }
        String entry = holder.entry(slot);
        if (entry == null) { open(player, query); return; }
        if (query.categories()) { open(player, new WarpBrowseQuery(entry, query.search(), 1, false)); return; }
        Warp current = service.getWarp(entry).orElse(null);
        if (current == null || !WarpBrowsing.visible(player, current)) { open(player, query); return; }
        if (current.closed()) {
            open(player, query);
            message(player, settings.controlMessages().closed(), settings, Placeholder.unparsed("warp", current.name()), Placeholder.unparsed("reason", current.closureReason())); return;
        }
        if (!player.hasPermission(Permissions.WARP_USE)) { message(player, settings.messages().noPermission(), settings, Placeholder.unparsed("warp", entry)); open(player, query); return; }
        player.closeInventory(); teleport.accept(player, entry);
    }

    public void forget(UUID id, long token) {
        for (;;) {
            Map<UUID, Long> old = opened.get(); if (!Long.valueOf(token).equals(old.get(id))) return;
            Map<UUID, Long> next = new HashMap<>(old); next.remove(id);
            if (opened.compareAndSet(old, Map.copyOf(next))) return;
        }
    }

    private void finish(UUID id, Pending action) {
        if (!action.done.compareAndSet(false, true)) return;
        for (;;) {
            Map<UUID, Pending> old = pending.get(); if (old.get(id) != action) return;
            Map<UUID, Pending> next = new HashMap<>(old); next.remove(id);
            if (pending.compareAndSet(old, Map.copyOf(next))) return;
        }
    }

    public void quit(UUID id) {
        Long token = opened.get().get(id); if (token != null) forget(id, token);
        Pending action = pending.get().get(id);
        if (action != null) { finish(id, action); ScheduledTask task = action.task.get(); if (task != null) task.cancel(); }
    }

    public void invalidate() {
        generation.incrementAndGet();
        Map<UUID, Long> views = opened.get();
        pending.get().forEach((id, action) -> { finish(id, action); ScheduledTask task = action.task.get(); if (task != null) task.cancel(); });
        if (!plugin.isEnabled()) { opened.set(Map.of()); return; }
        for (UUID id : views.keySet()) {
            Player player = Bukkit.getPlayer(id); if (player == null) { forget(id, views.get(id)); continue; }
            // Ownership and token checks prevent a reload task closing a replacement menu.
            boolean queued = defer(player, id, current -> {
                if (views.get(id).equals(opened.get().get(id))) current.closeInventory();
                forget(id, views.get(id));
            });
            if (!queued) forget(id, views.get(id));
        }
    }

    private ItemStack item(String icon, String name, List<String> lore, WarpConfig settings, TagResolver... values) {
        Material material = icon == null ? null : Material.matchMaterial(icon);
        if (material == null || !material.isItem() || material.isAir()) material = Material.BOOK;
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageFormatter.inventoryText(text(name, settings, values)));
            meta.lore(lore.stream().map(line -> MessageFormatter.inventoryText(text(line, settings, values))).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void message(Player player, String template, WarpConfig settings, TagResolver... values) { player.sendMessage(text(template, settings, values)); }
    private Component text(String template, WarpConfig settings, TagResolver... values) {
        TagResolver[] all = new TagResolver[values.length + 1];
        all[0] = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(settings.messages().prefix())); System.arraycopy(values, 0, all, 1, values.length);
        return miniMessage.deserialize(MessageFormatter.toMiniMessage(template), all);
    }
}
