package com.lunatech.tpcore.module.home.command;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.gui.HomeMenus;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import com.lunatech.tpcore.util.MessageFormatter;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import static com.lunatech.tpcore.module.home.messaging.HomeFeedback.send;
import static com.lunatech.tpcore.module.home.messaging.HomeFeedback.showStatus;

public final class HomeCommandRegistry {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final Supplier<DefaultHomeService> service;
    private final Supplier<HomeConfig> settings;
    private final HomeMenus menus;

    public HomeCommandRegistry(JavaPlugin plugin, Supplier<DefaultHomeService> service, Supplier<HomeConfig> settings, HomeMenus menus) {
        this.plugin = plugin; this.service = service; this.settings = settings;
        this.menus = menus;
    }

    public void registerAll() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> register(event.registrar()));
    }

    void register(Commands commands) {
        SuggestionProvider<CommandSourceStack> names = (context, builder) -> {
            DefaultHomeService current = service.get();
            if (current == null || !(context.getSource().getSender() instanceof Player player)) return builder.buildFuture();
            String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
            return current.list(player.getUniqueId(), settings.get().runtime().listPageSize(), 0).handle((homes, failure) -> {
                if (failure == null) homes.forEach(home -> {
                    if (home.name().toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(home.name());
                });
                return builder.build();
            });
        };
        commands.register(Commands.literal("home").requires(src -> canAccessHome(src.getSender()))
            .executes(ctx -> bareHome(ctx.getSource().getSender()))
            .then(Commands.literal("gui")
                .requires(src -> src.getSender() instanceof Player player && HomeMenus.canOpenMain(player))
                .executes(ctx -> openGui(ctx.getSource().getSender(), false))
                .then(Commands.literal("set").requires(src -> src.getSender().hasPermission(Permissions.HOME_SET))
                    .then(Commands.argument("token", StringArgumentType.word())
                        .then(Commands.argument("new-name", StringArgumentType.word()).executes(ctx -> submitName(
                            ctx.getSource().getSender(), StringArgumentType.getString(ctx, "token"),
                            StringArgumentType.getString(ctx, "new-name"))))))
                .then(Commands.literal("cancel").requires(src -> src.getSender().hasPermission(Permissions.HOME_SET))
                    .then(Commands.argument("token", StringArgumentType.word()).executes(ctx -> submitName(
                        ctx.getSource().getSender(), StringArgumentType.getString(ctx, "token"), null)))))
            .then(Commands.argument("name", StringArgumentType.string())
                .requires(src -> src.getSender().hasPermission(Permissions.HOME_USE))
                .suggests((ctx, builder) -> names.getSuggestions(ctx, builder).thenApply(suggestions -> {
                    if (suggestions.getList().stream().noneMatch(entry -> entry.getText().equalsIgnoreCase("gui"))) return suggestions;
                    var quoted = builder.createOffset(builder.getStart());
                    suggestions.getList().forEach(entry -> quoted.suggest(entry.getText().equalsIgnoreCase("gui")
                        ? "\"" + entry.getText() + "\"" : entry.getText()));
                    return quoted.build();
                }))
                .executes(ctx -> execute(ctx.getSource().getSender(), "home", StringArgumentType.getString(ctx, "name"), false)))
            .build(), "Teleport to a saved home", List.of());
        commands.register(Commands.literal("sethome").requires(src -> src.getSender().hasPermission(Permissions.HOME_SET))
            .executes(ctx -> execute(ctx.getSource().getSender(), "set", settings.get().defaultHomeName(), false))
            .then(Commands.argument("name", StringArgumentType.word()).suggests(names)
                .executes(ctx -> execute(ctx.getSource().getSender(), "set", StringArgumentType.getString(ctx, "name"), false))
                .then(Commands.literal("-f")
                    .executes(ctx -> execute(ctx.getSource().getSender(), "set", StringArgumentType.getString(ctx, "name"), true))))
            .build(), "Save a home at your current location", List.of());
        commands.register(Commands.literal("delhome").requires(src -> src.getSender().hasPermission(Permissions.HOME_DEL))
            .then(Commands.argument("name", StringArgumentType.word()).suggests(names)
                .executes(ctx -> execute(ctx.getSource().getSender(), "delete", StringArgumentType.getString(ctx, "name"), false)))
            .build(), "Delete and archive a home", List.of("removehome"));
        commands.register(Commands.literal("homerestore").requires(src -> src.getSender().hasPermission(Permissions.HOME_SET))
            .then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> execute(ctx.getSource().getSender(), "restore", StringArgumentType.getString(ctx, "name"), false)))
            .build(), "Restore your latest archived home with this name", List.of());
        commands.register(Commands.literal("homes").requires(src -> src.getSender().hasPermission(Permissions.HOME_LIST))
            .executes(ctx -> settings.get().gui().enabled() ? openGui(ctx.getSource().getSender(), true)
                : list(ctx.getSource().getSender(), 1))
            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> list(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "page"))))
            .build(), "List your saved homes", List.of("listhomes"));
        commands.register(Commands.literal("homeother").requires(src -> src.getSender().hasPermission(Permissions.HOME_OTHER))
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(player.getName());
                    }
                    return builder.buildFuture();
                })
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> other(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "target"),
                        StringArgumentType.getString(ctx, "name")))))
            .build(), "Visit another player's home", List.of());
        commands.register(Commands.literal("homebenchmark").requires(src -> src.getSender().hasPermission(Permissions.HOME_ADMIN_BENCHMARK))
            .executes(ctx -> benchmark(ctx.getSource().getSender(), 100))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                .executes(ctx -> benchmark(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "count"))))
            .build(), "Measure one bounded read from Home storage", List.of("homebench"));
    }

    private boolean canAccessHome(CommandSender sender) {
        return sender.hasPermission(Permissions.HOME_USE) || (settings.get().gui().enabled()
            && sender instanceof Player player && HomeMenus.canOpenMain(player));
    }

    private int bareHome(CommandSender sender) {
        HomeConfig config = settings.get();
        if (config.gui().enabled() && sender instanceof Player player) {
            menus.bareHome(player);
            return Command.SINGLE_SUCCESS;
        }
        if (sender.hasPermission(Permissions.HOME_USE)) return execute(sender, "home", config.defaultHomeName(), false);
        return 0;
    }

    private int openGui(CommandSender sender, boolean list) {
        if (sender instanceof Player player) {
            if (list) menus.homes(player); else menus.main(player);
        } else send(sender, settings.get(), settings.get().messages().onlyPlayers());
        return Command.SINGLE_SUCCESS;
    }

    private int submitName(CommandSender sender, String token, String name) {
        if (sender instanceof Player player) menus.submit(player, token, name);
        return Command.SINGLE_SUCCESS;
    }

    private DefaultHomeService available(CommandSender sender, HomeConfig config) {
        if (!(sender instanceof Player)) { send(sender, config, config.messages().onlyPlayers()); return null; }
        var current = service.get();
        if (!config.enabled()) send(sender, config, config.messages().moduleDisabled());
        else if (current == null) send(sender, config, config.runtime().message("not-ready"));
        else return current;
        return null;
    }

    private int execute(CommandSender sender, String operation, String name, boolean force) {
        HomeConfig config = settings.get();
        var current = available(sender, config);
        if (current == null || !(sender instanceof Player player)) return Command.SINGLE_SUCCESS;
        int limit = DefaultHomeService.homeLimit(player, config);
        var result = switch (operation) {
            case "set" -> current.setHome(player, name, force);
            case "delete" -> current.deleteHome(player, name);
            case "restore" -> current.restoreHome(player, name);
            default -> current.teleport(player, player.getUniqueId(), name, Permissions.HOME_USE, false);
        };
        reply(current, player, result, (online, status) -> showStatus(online, config, operation, name, status, limit,
            current.remainingCooldown(online.getUniqueId())));
        return Command.SINGLE_SUCCESS;
    }

    private int other(CommandSender sender, String target, String name) {
        HomeConfig config = settings.get();
        var current = available(sender, config);
        if (current == null || !(sender instanceof Player player)) return Command.SINGLE_SUCCESS;
        var owner = Bukkit.getOfflinePlayerIfCached(target);
        if (owner == null) owner = Bukkit.getPlayerExact(target);
        if (owner == null) {
            send(player, config, config.runtime().message("unknown-owner"), Placeholder.unparsed("target", target));
            return Command.SINGLE_SUCCESS;
        }
        reply(current, player, current.teleport(player, owner.getUniqueId(), name, Permissions.HOME_OTHER, false), (online, status) -> {
            if (status == HomeResultStatus.HOME_NOT_FOUND)
                send(online, config, config.messages().homeNotFoundOther(), Placeholder.unparsed("target", target), Placeholder.unparsed("home", name));
            else showStatus(online, config, "home", name, status, 0, current.remainingCooldown(online.getUniqueId()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandSender sender, int page) {
        HomeConfig config = settings.get();
        var current = available(sender, config);
        if (current == null || !(sender instanceof Player player)) return Command.SINGLE_SUCCESS;
        int size = config.runtime().listPageSize();
        reply(current, player, current.list(player.getUniqueId(), size + 1, (long) (page - 1) * size), (online, homes) -> {
            if (!online.hasPermission(Permissions.HOME_LIST)) return;
            if (homes.isEmpty()) { send(online, config, page == 1 ? config.messages().homeListEmpty() : config.runtime().message("empty-page")); return; }
            send(online, config, config.runtime().message("list-page"), Placeholder.unparsed("page", Integer.toString(page)));
            homes.stream().limit(size).forEach(home -> {
                String item = config.messages().homeListItem();
                if (DefaultHomeService.valid(home.name())) {
                    String argument = home.name().equalsIgnoreCase("gui") ? "\"" + home.name() + "\"" : home.name();
                    item = item.replace("/home <name>", "/home " + argument);
                }
                online.sendMessage(MessageFormatter.parse(MINI, item, Placeholder.unparsed("name", home.name()),
                    Placeholder.unparsed("world", home.worldName()), Placeholder.unparsed("x", "%.1f".formatted(home.x())),
                    Placeholder.unparsed("y", "%.1f".formatted(home.y())), Placeholder.unparsed("z", "%.1f".formatted(home.z()))));
            });
            if (homes.size() > size && page < Integer.MAX_VALUE) {
                String next = Integer.toString(page + 1);
                send(online, config, config.runtime().message("list-next").replace("/homes <page>", "/homes " + next),
                    Placeholder.unparsed("page", next));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int benchmark(CommandSender sender, int count) {
        HomeConfig config = settings.get();
        var current = available(sender, config);
        if (current == null || !(sender instanceof Player player)) return Command.SINGLE_SUCCESS;
        if (!config.benchmark().enabled()) { send(sender, config, config.messages().benchmarkDisabled()); return Command.SINGLE_SUCCESS; }
        long start = System.nanoTime();
        reply(current, player, current.list(player.getUniqueId(), count, 0)
            .thenApply(homes -> new Timing(homes.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))), (online, timing) -> {
                if (online.hasPermission(Permissions.HOME_ADMIN_BENCHMARK)) send(online, config, config.runtime().message("benchmark-read"),
                    Placeholder.unparsed("count", Integer.toString(timing.rows())), Placeholder.unparsed("millis", Long.toString(timing.millis())));
            });
        return Command.SINGLE_SUCCESS;
    }

    private record Timing(int rows, long millis) {}

    @FunctionalInterface
    private interface PlayerReply<T> { void accept(Player player, T result); }

    private <T> void reply(DefaultHomeService current, Player player, CompletableFuture<T> result, PlayerReply<T> action) {
        var handle = new WeakReference<>(player);
        var scheduler = player.getScheduler();
        result.whenComplete((value, error) -> {
            if (service.get() != current || !plugin.isEnabled()) return;
            HomeResultStatus failure = error == null ? null : current.storageFailure("command", error);
            try {
                scheduler.run(plugin, task -> {
                    Player online = handle.get();
                    if (online == null || service.get() != current) return;
                    if (failure != null) showStatus(online, settings.get(), "", "", failure, 0, 0);
                    else action.accept(online, value);
                }, null);
            } catch (RuntimeException rejected) {
                if (plugin.isEnabled() && service.get() == current) plugin.getSLF4JLogger().warn("Home reply could not be scheduled", rejected);
            }
        });
    }
}
