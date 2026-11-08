package com.lunatech.tpcore.module.home.gui;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.model.HomePage;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.Plugin;

import static com.lunatech.tpcore.module.home.gui.HomeMenuRenderer.BACK;
import static com.lunatech.tpcore.module.home.gui.HomeMenuRenderer.CANCEL;
import static com.lunatech.tpcore.module.home.gui.HomeMenuRenderer.CONFIRM;
import static com.lunatech.tpcore.module.home.gui.HomeMenuRenderer.LIST;
import static com.lunatech.tpcore.module.home.gui.HomeMenuRenderer.NEXT;
import static com.lunatech.tpcore.module.home.gui.HomeMenuRenderer.PREVIOUS;
import static com.lunatech.tpcore.module.home.gui.HomeMenuRenderer.SET;
import static com.lunatech.tpcore.module.home.messaging.HomeFeedback.send;
import static com.lunatech.tpcore.module.home.messaging.HomeFeedback.showStatus;

public final class HomeMenus {
    private final Plugin plugin;
    private final Supplier<DefaultHomeService> services;
    private final Supplier<HomeConfig> settings;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    // Accessed on the player's owner, apart from immutable identity and the published prompt deadline.
    private static final class Session {
        final UUID player;
        final HomeConfig config;
        final DefaultHomeService service;
        UUID view;
        UUID prompt;
        volatile long promptDeadline;
        boolean pending;
        boolean closing;

        Session(UUID player, HomeConfig config, DefaultHomeService service) {
            this.player = player;
            this.config = config;
            this.service = service;
        }
    }

    public HomeMenus(Plugin plugin, Supplier<DefaultHomeService> services, Supplier<HomeConfig> settings) {
        this(plugin, services, settings, System::nanoTime);
    }

    HomeMenus(Plugin plugin, Supplier<DefaultHomeService> services, Supplier<HomeConfig> settings, LongSupplier nanoTime) {
        this.plugin = plugin;
        this.services = services;
        this.settings = settings;
        this.nanoTime = nanoTime;
    }

    public void main(Player player) { start(player, false, false); }
    public void homes(Player player) { start(player, true, false); }
    public void bareHome(Player player) { start(player, false, true); }

    private void start(Player player, boolean list, boolean auto) {
        HomeConfig config = settings.get();
        if (!config.enabled()) { send(player, config, config.messages().moduleDisabled()); return; }
        if (!config.gui().enabled()) { message(player, config, "disabled"); return; }
        if (list ? !player.hasPermission(Permissions.HOME_LIST) : !auto && !canOpenMain(player)) {
            status(player, config, HomeResultStatus.NO_PERMISSION);
            return;
        }
        DefaultHomeService service = services.get();
        if (service == null) { status(player, config, HomeResultStatus.NOT_READY); return; }
        Session session = new Session(player.getUniqueId(), config, service);
        boolean admitted;
        synchronized (sessions) {
            long now = nanoTime.getAsLong();
            if (sessions.size() >= config.runtime().maxCooldowns()) {
                sessions.values().removeIf(previous -> previous.promptDeadline != 0 && now - previous.promptDeadline >= 0);
            }
            Session previous = sessions.get(session.player);
            admitted = previous != null ? !previous.pending : sessions.size() < config.runtime().maxCooldowns();
            if (admitted) {
                if (previous != null) session.view = previous.view;
                sessions.put(session.player, session);
            }
        }
        if (!admitted) { status(player, config, HomeResultStatus.BUSY); return; }
        if (!live(session)) { forget(session); return; }
        fetch(player, session, 1, (online, page) -> {
            if (auto && page.total() == 1) travel(online, session, page.entries().getFirst(), 0);
            else if (list || (auto && page.total() > 1)) showList(online, session, page);
            else showMain(online, session, page.total());
        });
    }

    public void click(Player player, HomeMenuHolder holder, int slot, ClickType click) {
        UUID playerId = player.getUniqueId();
        if (!holder.player().equals(playerId)) return;
        Session session = sessions.get(playerId);
        if (!live(session) || !holder.token().equals(session.view)) {
            if (holder.beginClose()) {
                Object staleView = player.getOpenInventory();
                player.getScheduler().run(plugin, task -> {
                    if (player.getOpenInventory() == staleView) player.closeInventory();
                }, null);
            }
            return;
        }
        if (session.pending) return;
        session.pending = true;
        if (player.getScheduler().run(plugin, task -> {
            if (!live(session) || !holder.token().equals(session.view)) return;
            session.pending = false;
            act(player, session, holder, slot, click);
        }, () -> forget(session)) == null) forget(session);
    }

    private void act(Player player, Session session, HomeMenuHolder holder, int slot, ClickType click) {
        switch (holder.screen()) {
            case MENU -> {
                if (click != ClickType.LEFT) return;
                if (slot == SET) {
                    if (require(player, session, Permissions.HOME_SET)) prompt(player, session);
                } else if (slot == LIST) readList(player, session, 1);
            }
            case HOMES -> {
                if (!require(player, session, Permissions.HOME_LIST)) return;
                if (click == ClickType.LEFT) {
                    if (slot == BACK) {
                        fetch(player, session, 1, (online, page) -> showMain(online, session, page.total()));
                        return;
                    }
                    if (slot == PREVIOUS && holder.page() > 1) { readList(player, session, holder.page() - 1); return; }
                    if (slot == NEXT && holder.page() < holder.pages()) { readList(player, session, holder.page() + 1); return; }
                }
                int index = HomeMenuRenderer.index(slot);
                if (index < 0 || index >= holder.entries().size()) return;
                HomePage.Entry entry = holder.entries().get(index);
                if (click == ClickType.LEFT) travel(player, session, entry, holder.page());
                else if (require(player, session, Permissions.HOME_DEL)) {
                    open(player, session, HomeMenuRenderer.confirm(session.player, UUID.randomUUID(), session.config.gui(), holder.page(), entry));
                }
            }
            case CONFIRM -> {
                if (click != ClickType.LEFT || !require(player, session, Permissions.HOME_LIST)) return;
                if (slot == CANCEL) readList(player, session, holder.page());
                if (slot == CONFIRM && require(player, session, Permissions.HOME_DEL)) {
                    HomePage.Entry entry = holder.selected();
                    long page = holder.page();
                    complete(player, session, session.service.deleteHome(player, entry), (online, result) -> {
                        feedback(online, session, "delete", entry.home().name(), result);
                        if (result == HomeResultStatus.SUCCESS || result == HomeResultStatus.HOME_CHANGED) {
                            readList(online, session, page);
                        }
                    });
                }
            }
        }
    }

    private void travel(Player player, Session session, HomePage.Entry entry, long returnPage) {
        if (!require(player, session, Permissions.HOME_USE)) {
            if (returnPage == 0) forget(session);
            return;
        }
        close(player, session);
        complete(player, session, session.service.teleport(player, entry), (online, result) -> {
            feedback(online, session, "home", entry.home().name(), result);
            if (result == HomeResultStatus.HOME_CHANGED && returnPage > 0 && online.hasPermission(Permissions.HOME_LIST)) {
                readList(online, session, returnPage);
            } else forget(session);
        });
    }

    private void readList(Player player, Session session, long page) {
        if (require(player, session, Permissions.HOME_LIST)) fetch(player, session, page, (online, result) -> showList(online, session, result));
    }

    private void showList(Player player, Session session, HomePage page) {
        if (!require(player, session, Permissions.HOME_LIST)) {
            if (session.view == null) forget(session);
            return;
        }
        open(player, session, HomeMenuRenderer.homes(session.player, UUID.randomUUID(), session.config.gui(), page,
            player.hasPermission(Permissions.HOME_USE), player.hasPermission(Permissions.HOME_DEL)));
    }

    private void showMain(Player player, Session session, long count) {
        if (!canOpenMain(player)) {
            status(player, session.config, HomeResultStatus.NO_PERMISSION);
            if (session.view == null) forget(session);
            return;
        }
        open(player, session, HomeMenuRenderer.main(session.player, UUID.randomUUID(), session.config.gui(), count,
            DefaultHomeService.homeLimit(player, session.config), player.hasPermission(Permissions.HOME_SET),
            player.hasPermission(Permissions.HOME_LIST)));
    }

    private void prompt(Player player, Session session) {
        close(player, session);
        session.prompt = UUID.randomUUID();
        session.promptDeadline = nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(session.config.gui().promptTimeoutSeconds());
        String prefix = "/home gui set " + session.prompt + " ";
        var messages = session.config.gui().messages();
        send(player, session.config, messages.get("prompt") + " <click:suggest_command:'" + prefix + "'>"
            + messages.get("enter-name") + "</click>");
        String defaultName = session.config.defaultHomeName();
        if (DefaultHomeService.valid(defaultName) && defaultName.length() <= 32) {
            send(player, session.config, "<click:run_command:'" + prefix + defaultName + "'>" + messages.get("use-default")
                + "</click>", Placeholder.unparsed("name", defaultName));
        }
        send(player, session.config, "<click:run_command:'/home gui cancel " + session.prompt + "'>" + messages.get("cancel-prompt") + "</click>");
    }

    public void submit(Player player, String token, String name) {
        UUID playerId = player.getUniqueId();
        Session session = sessions.get(playerId);
        boolean expired = session != null && session.prompt != null && nanoTime.getAsLong() - session.promptDeadline >= 0;
        if (!live(session) || session.prompt == null || !session.prompt.toString().equals(token) || expired) {
            if (expired) forget(session);
            message(player, settings.get(), "prompt-expired");
            return;
        }
        if (!require(player, session, Permissions.HOME_SET)) { forget(session); return; }
        session.prompt = null;
        session.promptDeadline = 0;
        if (name == null) { message(player, session.config, "prompt-cancelled"); forget(session); return; }
        complete(player, session, session.service.createHome(player, name), (online, result) -> {
            feedback(online, session, "set", name, result);
            forget(session);
        });
    }

    private void open(Player player, Session session, HomeMenuHolder holder) {
        if (!live(session)) return;
        UUID previous = session.view;
        session.view = holder.token();
        if (player.openInventory(holder.getInventory()) == null) {
            session.view = previous;
            if (previous == null) forget(session);
        }
    }

    private void close(Player player, Session session) {
        if (session.view == null) return;
        session.closing = true;
        session.view = null;
        try { player.closeInventory(); }
        finally { session.closing = false; }
    }

    public void opened(UUID player, HomeMenuHolder holder) {
        Session session = sessions.get(player);
        if (session != null && (holder == null || !holder.token().equals(session.view))) forget(session);
    }

    public void closed(UUID player, HomeMenuHolder holder) {
        Session session = sessions.get(player);
        if (session == null || session.closing || session.prompt != null) return;
        if (session.view == null ? session.pending : holder != null && holder.token().equals(session.view)) forget(session);
    }

    public void quit(UUID player) { sessions.remove(player); }
    public void invalidate() { sessions.clear(); }

    private void fetch(Player player, Session session, long page, BiConsumer<Player, HomePage> result) {
        complete(player, session, session.service.page(session.player, page), result);
    }

    private <T> void complete(Player player, Session session, CompletableFuture<T> future, BiConsumer<Player, T> action) {
        session.pending = true;
        var scheduler = player.getScheduler();
        var handle = new WeakReference<>(player);
        future.whenComplete((value, failure) -> {
            if (!live(session)) return;
            try {
                if (scheduler.run(plugin, task -> {
                    Player online = handle.get();
                    if (online == null || !live(session)) { forget(session); return; }
                    session.pending = false;
                    if (failure == null) action.accept(online, value);
                    else {
                        status(online, session.config, session.service.storageFailure("menu", failure));
                        if (session.view == null) forget(session);
                    }
                }, () -> forget(session)) == null) forget(session);
            } catch (RuntimeException rejected) {
                forget(session);
                if (plugin.isEnabled()) plugin.getSLF4JLogger().warn("Home menu result could not be scheduled", rejected);
            }
        });
    }

    private boolean live(Session session) {
        return session != null && sessions.get(session.player) == session
            && settings.get() == session.config && services.get() == session.service;
    }

    private void forget(Session session) { sessions.remove(session.player, session); }

    public static boolean canOpenMain(Player player) {
        return player.hasPermission(Permissions.HOME_SET) || player.hasPermission(Permissions.HOME_LIST);
    }

    private static boolean require(Player player, Session session, String permission) {
        if (player.hasPermission(permission)) return true;
        status(player, session.config, HomeResultStatus.NO_PERMISSION);
        return false;
    }

    private static void feedback(Player player, Session session, String operation, String name, HomeResultStatus result) {
        showStatus(player, session.config, operation, name, result, DefaultHomeService.homeLimit(player, session.config),
            session.service.remainingCooldown(session.player));
    }

    private static void status(Player player, HomeConfig config, HomeResultStatus status) {
        showStatus(player, config, "", "", status, 0, 0);
    }

    private static void message(Player player, HomeConfig config, String key) {
        send(player, config, config.gui().messages().get(key));
    }
}
