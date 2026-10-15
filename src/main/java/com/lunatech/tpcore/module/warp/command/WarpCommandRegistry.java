package com.lunatech.tpcore.module.warp.command;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.gui.WarpBrowser;
import com.lunatech.tpcore.module.warp.model.WarpBrowseQuery;
import com.lunatech.tpcore.module.warp.util.WarpBrowsing;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.kyori.adventure.text.event.ClickEvent;
import com.lunatech.tpcore.module.warp.model.WarpEdit;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.util.MessageFormatter;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.model.WarpImportPlan;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.World;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class WarpCommandRegistry {

    private static final ClickEvent WARP_RUN_TEMPLATE = ClickEvent.runCommand("/warp <name>");
    private static final ClickEvent WARP_SUGGEST_TEMPLATE = ClickEvent.suggestCommand("/warp <name>");

    private final JavaPlugin plugin;
    private final Supplier<WarpService> warpServiceSupplier;
    private final Supplier<WarpConfig> configSupplier;
    private final WarpBrowser browser;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public WarpCommandRegistry(JavaPlugin plugin, Supplier<WarpService> warpServiceSupplier, Supplier<WarpConfig> configSupplier) {
        this(plugin, warpServiceSupplier, configSupplier, null);
    }

    public WarpCommandRegistry(JavaPlugin plugin, Supplier<WarpService> warpServiceSupplier, Supplier<WarpConfig> configSupplier, WarpBrowser browser) {
        this.browser = browser;
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.warpServiceSupplier = Objects.requireNonNull(warpServiceSupplier, "warpServiceSupplier cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    public void registerAll() {
        registerAll(null);
    }

    public void registerAll(WarpSignCommandRegistry signs) {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            SuggestionProvider<CommandSourceStack> warpSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                WarpService service = warpServiceSupplier.get();
                if (service != null) {
                    CommandSender sender = context.getSource().getSender();
                    for (Warp warp : service.getAllWarps()) {
                        if (WarpBrowsing.visible(sender, warp) && warp.name().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                            builder.suggest(warp.name());
                        }
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> categorySuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                WarpService service = warpServiceSupplier.get();
                if (service != null) {
                    for (String category : service.getAllWarps().stream().filter(warp -> publiclyVisible(context.getSource().getSender(), warp)).map(Warp::category).distinct().toList()) {
                        if (category.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                            builder.suggest(category);
                        }
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> playerSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(player.getName());
                    }
                }
                return builder.buildFuture();
            };

            // /warp [name] [password]
            commands.register(
                Commands.literal("warp")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_USE))
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        if (sender instanceof Player player) {
                            return executeWarpsList(player, null, 1);
                        }
                        sendMessage(sender, configSupplier.get().messages().warpUsage());
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(warpSuggestions)
                        .executes(ctx -> executeWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), null))
                        .then(Commands.argument("password", StringArgumentType.string())
                            .executes(ctx -> executeWarp(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "password")
                            ))
                        )
                    )
                    .build(),
                "Teleport to a designated server warp",
                List.of()
            );

            // /setwarp [name] [-f] [category] [password]
            commands.register(
                Commands.literal("setwarp")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_SET))
                    .executes(ctx -> {
                        sendMessage(ctx.getSource().getSender(), configSupplier.get().messages().setWarpUsage());
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(warpSuggestions)
                        .executes(ctx -> executeSetWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), false, null, null))
                        .then(Commands.literal("-f")
                            .executes(ctx -> executeSetWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), true, null, null))
                            .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(categorySuggestions)
                                .executes(ctx -> executeSetWarp(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "name"),
                                    true,
                                    null,
                                    StringArgumentType.getString(ctx, "category")
                                ))
                                .then(Commands.argument("password", StringArgumentType.string())
                                    .executes(ctx -> executeSetWarp(
                                        ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "name"),
                                        true,
                                        StringArgumentType.getString(ctx, "password"),
                                        StringArgumentType.getString(ctx, "category")
                                    ))
                                )
                            )
                        )
                        .then(Commands.argument("category", StringArgumentType.word())
                            .suggests(categorySuggestions)
                            .executes(ctx -> executeSetWarp(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "name"),
                                false,
                                null,
                                StringArgumentType.getString(ctx, "category")
                            ))
                            .then(Commands.literal("-f")
                                .executes(ctx -> executeSetWarp(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "name"),
                                    true,
                                    null,
                                    StringArgumentType.getString(ctx, "category")
                                ))
                            )
                            .then(Commands.argument("password", StringArgumentType.string())
                                .executes(ctx -> executeSetWarp(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "name"),
                                    false,
                                    StringArgumentType.getString(ctx, "password"),
                                    StringArgumentType.getString(ctx, "category")
                                ))
                                .then(Commands.literal("-f")
                                    .executes(ctx -> executeSetWarp(
                                        ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "name"),
                                        true,
                                        StringArgumentType.getString(ctx, "password"),
                                        StringArgumentType.getString(ctx, "category")
                                    ))
                                )
                            )
                        )
                    )
                    .build(),
                "Set a server warp location",
                List.of("createwarp")
            );

            // /delwarp [name]
            commands.register(
                Commands.literal("delwarp")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_DEL))
                    .executes(ctx -> {
                        sendMessage(ctx.getSource().getSender(), configSupplier.get().messages().delWarpUsage());
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(warpSuggestions)
                        .executes(ctx -> executeDelWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), false))
                        .then(Commands.literal("confirm").executes(ctx -> executeDelWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), true)))
                    )
                    .build(),
                "Delete a server warp location",
                List.of("removewarp")
            );

            registerBrowseCommands(commands);

            // /warpother [target] [name]
            commands.register(
                Commands.literal("warpother")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_OTHER))
                    .executes(ctx -> {
                        sendMessage(ctx.getSource().getSender(), configSupplier.get().messages().warpOtherUsage());
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(playerSuggestions)
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(warpSuggestions)
                            .executes(ctx -> executeWarpOther(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "target"),
                                StringArgumentType.getString(ctx, "name")
                            ))
                        )
                    )
                    .build(),
                "Teleport another player to a warp",
                List.of()
            );
            registerStaffCommands(commands);
            registerImportCommands(commands);
            if (signs != null) signs.register(commands);
        });
    }

    private void registerBrowseCommands(Commands commands) {
        commands.register(browseRoot("warps", false).then(browseRoot("gui", true)).build(), configSupplier.get().browsing().messages().commandDescription(), List.of("listwarps", "warplist"));
    }

    private void registerImportCommands(Commands commands) {
        var preview = Commands.literal("preview").executes(context -> executeImport(context.getSource().getSender(), "skip", null));
        for (String policy : List.of("skip", "replace", "rename")) preview.then(Commands.literal(policy)
            .executes(context -> executeImport(context.getSource().getSender(), policy, null)));
        commands.register(Commands.literal("warpimport").requires(source -> staffPermission(source.getSender(), Permissions.WARP_IMPORT))
            .executes(context -> { sendMessage(context.getSource().getSender(), configSupplier.get().essentialsImport().messages().usage()); return Command.SINGLE_SUCCESS; })
            .then(Commands.literal("essentials").then(preview).then(Commands.literal("confirm")
                .then(Commands.argument("token", StringArgumentType.word()).executes(context -> executeImport(context.getSource().getSender(), null, StringArgumentType.getString(context, "token"))))))
            .build(), configSupplier.get().essentialsImport().messages().description(), List.of());
    }

    private int executeImport(CommandSender sender, String policy, String rawToken) {
        WarpConfig settings = configSupplier.get();
        if (!settings.enabled()) { sendDisabledMessage(sender); return Command.SINGLE_SUCCESS; }
        if (!staffPermission(sender, Permissions.WARP_IMPORT)) { sendMessage(sender, settings.messages().noPermission()); return Command.SINGLE_SUCCESS; }
        UUID id = sender instanceof Player player ? player.getUniqueId() : null;
        UUID actor = id == null ? new UUID(0, 0) : id;
        CommandSender console = id == null ? sender : null;
        UUID token;
        try { token = rawToken == null ? null : UUID.fromString(rawToken); }
        catch (IllegalArgumentException failure) { sendMessage(sender, settings.essentialsImport().messages().usage()); return Command.SINGLE_SUCCESS; }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                WarpService service = warpServiceSupplier.get();
                if (service == null || !service.isReady()) throw new IllegalStateException("NOT_READY");
                Map<String, WarpImportPlan.WorldData> worlds = new HashMap<>();
                for (World world : Bukkit.getWorlds()) {
                    var snapshot = new WarpImportPlan.WorldData(world.getUID(), world.getName(), world.getMinHeight(), world.getMaxHeight());
                    worlds.put("id:" + snapshot.id(), snapshot); worlds.put("name:" + snapshot.name().toLowerCase(Locale.ROOT), snapshot);
                }
                Runnable begin = () -> {
                    try {
                        if (token == null) service.previewEssentialsImport(actor, policy, worlds).whenComplete((plan, failure) -> {
                            if (failure != null) importFailure(id, console, settings, failure);
                            else {
                                var messages = settings.essentialsImport().messages();
                                sendReply(id, console, messages.preview(), Placeholder.unparsed("files", Integer.toString(plan.fingerprints().size())),
                                    Placeholder.unparsed("count", Integer.toString(plan.selected().size())), Placeholder.unparsed("conflicts", Integer.toString(plan.conflicts())),
                                    Placeholder.unparsed("invalid", Integer.toString(plan.invalid())), Placeholder.unparsed("policy", plan.policy()));
                                int limit = Math.min(plan.entries().size(), settings.essentialsImport().previewLines());
                                for (var entry : plan.entries().subList(0, limit)) sendReply(id, console, messages.entry(), Placeholder.unparsed("file", entry.file()),
                                    Placeholder.unparsed("name", entry.name()), Placeholder.unparsed("target", entry.target()), Placeholder.unparsed("reason", messages.reasons().getOrDefault(entry.reason(), entry.reason())));
                                if (limit < plan.entries().size()) sendReply(id, console, messages.omitted(), Placeholder.unparsed("count", Integer.toString(plan.entries().size() - limit)));
                                sendReply(id, console, messages.permissionNotice(), Placeholder.unparsed("required", Boolean.toString(settings.essentialsImport().permissionRequired())));
                                if (!plan.selected().isEmpty()) sendReply(id, console, messages.confirmation(), Placeholder.unparsed("token", plan.token().toString()),
                                    Placeholder.unparsed("seconds", Integer.toString(settings.essentialsImport().previewSeconds())));
                            }
                        });
                        else service.confirmEssentialsImport(actor, token, worlds).whenComplete((result, failure) -> {
                            if (failure != null) importFailure(id, console, settings, failure);
                            else sendReply(id, console, settings.essentialsImport().messages().success(), Placeholder.unparsed("count", Integer.toString(result.count())), Placeholder.unparsed("backup", result.backup()));
                        });
                    } catch (RuntimeException failure) { importFailure(id, console, settings, failure); }
                };
                if (id == null) begin.run();
                else {
                    Player handle = Bukkit.getPlayer(id);
                    if (handle != null) handle.getScheduler().run(plugin, ignored -> {
                        Player current = Bukkit.getPlayer(id);
                        if (current == null) return;
                        if (staffPermission(current, Permissions.WARP_IMPORT)) begin.run();
                        else sendMessage(current, configSupplier.get().messages().noPermission());
                    }, null);
                }
            } catch (RuntimeException failure) { importFailure(id, console, settings, failure); }
        });
        return Command.SINGLE_SUCCESS;
    }

    private void importFailure(UUID id, CommandSender console, WarpConfig settings, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause instanceof CompletionException) cause = cause.getCause();
        String code = cause.getMessage();
        String reason = settings.essentialsImport().messages().reasons().getOrDefault(code == null ? "IO" : code, settings.essentialsImport().messages().reasons().getOrDefault("IO", "IO"));
        if (code == null || !settings.essentialsImport().messages().reasons().containsKey(code) || code.equals("IO") || code.equals("SOURCE")) plugin.getSLF4JLogger().error("EssentialsX warp import failed", failure);
        sendReply(id, console, settings.essentialsImport().messages().failure(), Placeholder.unparsed("reason", reason));
    }

    private LiteralArgumentBuilder<CommandSourceStack> browseRoot(String label, boolean gui) {
        SuggestionProvider<CommandSourceStack> categories = (context, builder) -> {
            WarpService service = warpServiceSupplier.get();
            if (service != null && configSupplier.get().enabled()) {
                var page = WarpBrowsing.page(service.getAllWarps(), warp -> WarpBrowsing.visible(context.getSource().getSender(), warp), new WarpBrowseQuery(null, null, 1, false), 1);
                for (String category : page.categories()) if (category.startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) builder.suggest(category);
            }
            return builder.buildFuture();
        };
        return Commands.literal(label).requires(source -> source.getSender().hasPermission(Permissions.WARP_LIST))
            .executes(context -> browse(context.getSource().getSender(), null, null, 1, gui))
            .then(Commands.literal("help").executes(context -> {
                WarpConfig settings = configSupplier.get();
                sendMessage(context.getSource().getSender(), settings.enabled() ? settings.browsing().messages().usage() : settings.messages().moduleDisabled());
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("search").then(Commands.argument("search", StringArgumentType.string())
                .executes(context -> browse(context.getSource().getSender(), null, StringArgumentType.getString(context, "search"), 1, gui))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> browse(context.getSource().getSender(), null,
                    StringArgumentType.getString(context, "search"), IntegerArgumentType.getInteger(context, "page"), gui)))))
            .then(Commands.literal("categories").executes(context -> categoryList(context.getSource().getSender(), gui, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> categoryList(context.getSource().getSender(), gui, IntegerArgumentType.getInteger(context, "page")))))
            .then(Commands.literal("category").then(Commands.argument("category", StringArgumentType.string()).suggests(categories)
                .executes(context -> browse(context.getSource().getSender(), StringArgumentType.getString(context, "category"), null, 1, gui))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> browse(context.getSource().getSender(),
                    StringArgumentType.getString(context, "category"), null, IntegerArgumentType.getInteger(context, "page"), gui)))
                .then(Commands.literal("search").then(Commands.argument("search", StringArgumentType.string())
                    .executes(context -> browse(context.getSource().getSender(), StringArgumentType.getString(context, "category"), StringArgumentType.getString(context, "search"), 1, gui))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> browse(context.getSource().getSender(),
                        StringArgumentType.getString(context, "category"), StringArgumentType.getString(context, "search"), IntegerArgumentType.getInteger(context, "page"), gui)))))))
            .then(Commands.argument("query", StringArgumentType.word()).suggests(categories)
                .executes(context -> {
                    String query = StringArgumentType.getString(context, "query");
                    if (query.matches("\\d+")) {
                        int page; try { page = Integer.parseInt(query); } catch (NumberFormatException failure) { page = Integer.MAX_VALUE; }
                        return browse(context.getSource().getSender(), null, null, page, gui);
                    }
                    return browse(context.getSource().getSender(), query, null, 1, gui);
                })
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> browse(context.getSource().getSender(),
                    StringArgumentType.getString(context, "query"), null, IntegerArgumentType.getInteger(context, "page"), gui))));
    }

    private int browse(CommandSender sender, String category, String search, int page, boolean gui) {
        WarpConfig settings = configSupplier.get();
        if (search != null && (search.isBlank() || search.length() > settings.browsing().maxSearchLength())) {
            sendMessage(sender, settings.browsing().messages().invalidSearch(), Placeholder.unparsed("limit", Integer.toString(settings.browsing().maxSearchLength()))); return Command.SINGLE_SUCCESS;
        }
        if (gui || sender instanceof Player && settings.browsing().inventoryEnabled()) {
            if (!(sender instanceof Player player)) sendOnlyPlayersMessage(sender);
            else if (browser == null) sendMessage(sender, settings.browsing().messages().inventoryDisabled());
            else browser.open(player, new WarpBrowseQuery(category, search, page, false));
            return Command.SINGLE_SUCCESS;
        }
        return executeWarpsList(sender, category, search, page);
    }

    private int categoryList(CommandSender sender, boolean gui, int requestedPage) {
        WarpConfig settings = configSupplier.get(); WarpService service = warpServiceSupplier.get();
        if (!settings.enabled()) { sendDisabledMessage(sender); return Command.SINGLE_SUCCESS; }
        if (service == null || !service.isReady()) { sendMessage(sender, settings.messages().storageNotReady()); return Command.SINGLE_SUCCESS; }
        if (gui || sender instanceof Player && settings.browsing().inventoryEnabled()) {
            if (!(sender instanceof Player player)) sendOnlyPlayersMessage(sender);
            else if (browser != null) browser.open(player, new WarpBrowseQuery(null, null, requestedPage, true));
            else sendMessage(sender, settings.browsing().messages().inventoryDisabled());
            return Command.SINGLE_SUCCESS;
        }
        var page = WarpBrowsing.page(service.getAllWarps(), warp -> WarpBrowsing.visible(sender, warp), new WarpBrowseQuery(null, null, requestedPage, true), settings.warpsPerPage());
        if (page.categories().isEmpty()) sendMessage(sender, settings.messages().warpListEmpty());
        else {
            sendMessage(sender, settings.browsing().messages().categoriesHeader());
            int size = Math.max(1, Math.min(settings.warpsPerPage(), page.categories().size()));
            int start = (page.query().page() - 1) * size;
            for (String category : page.categories().subList(start, Math.min(start + size, page.categories().size()))) sender.sendMessage(browseText(settings.browsing().messages().categoryItem(), settings,
                Placeholder.unparsed("category", category)).clickEvent(ClickEvent.runCommand(browseCommand(new WarpBrowseQuery(category, null, 1, false), 1))));
            sendMessage(sender, settings.browsing().messages().navigation(), Placeholder.unparsed("page", Integer.toString(page.query().page())), Placeholder.unparsed("maxpages", Integer.toString(page.pages())), Placeholder.unparsed("total", Integer.toString(page.total())));
            if (page.query().page() > 1) sender.sendMessage(browseText(settings.browsing().messages().previous(), settings).clickEvent(ClickEvent.runCommand(browseCommand(page.query(), page.query().page() - 1))));
            if (page.query().page() < page.pages()) sender.sendMessage(browseText(settings.browsing().messages().next(), settings).clickEvent(ClickEvent.runCommand(browseCommand(page.query(), page.query().page() + 1))));
        }
        return Command.SINGLE_SUCCESS;
    }

    public void teleportFromBrowser(Player player, String name) { executeWarp(player, name, null); }
    public void teleportFromSign(Player player, String name) { executeWarp(player, name, null); }

    private String browseCommand(WarpBrowseQuery query, int page) {
        if (query.categories()) return "/warps categories " + page;
        return "/warps" + (query.category().isEmpty() ? "" : " category " + StringArgumentType.escapeIfRequired(query.category()))
            + (query.search().isEmpty() ? "" : " search " + StringArgumentType.escapeIfRequired(query.search())) + " " + page;
    }

    private Component browseText(String template, WarpConfig settings, TagResolver... resolvers) {
        TagResolver[] all = new TagResolver[resolvers.length + 1]; all[0] = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(settings.messages().prefix()));
        System.arraycopy(resolvers, 0, all, 1, resolvers.length); return miniMessage.deserialize(MessageFormatter.toMiniMessage(template), all);
    }

    private boolean staffPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.hasPermission(Permissions.WARP_ADMIN);
    }

    private boolean anyEditPermission(CommandSender sender) {
        return staffPermission(sender, Permissions.WARP_INFO) || staffPermission(sender, Permissions.WARP_EDIT_RENAME)
            || staffPermission(sender, Permissions.WARP_EDIT_RELOCATE) || staffPermission(sender, Permissions.WARP_EDIT_CATEGORY)
            || staffPermission(sender, Permissions.WARP_EDIT_PASSWORD) || staffPermission(sender, Permissions.WARP_EDIT_PERMISSION)
            || staffPermission(sender, Permissions.WARP_EDIT_AVAILABILITY) || staffPermission(sender, Permissions.WARP_EDIT_VISIBILITY);
    }

    private void registerStaffCommands(Commands commands) {
        commands.register(Commands.literal("warpstatus")
            .requires(source -> staffPermission(source.getSender(), Permissions.WARP_STATUS))
            .executes(context -> warpStatus(context.getSource().getSender(), false, 1))
            .then(Commands.literal("payments")
                .requires(source -> staffPermission(source.getSender(), Permissions.WARP_PAYMENTS))
                .executes(context -> warpStatus(context.getSource().getSender(), true, 1))
                .then(Commands.literal("acknowledge").requires(source -> staffPermission(source.getSender(), Permissions.WARP_PAYMENTS_ACKNOWLEDGE))
                    .then(Commands.argument("ticket", StringArgumentType.word())
                        .then(Commands.argument("evidence-note", StringArgumentType.greedyString()).executes(context ->
                            paymentAcknowledgement(context.getSource().getSender(), StringArgumentType.getString(context, "ticket"), StringArgumentType.getString(context, "evidence-note"))))))
                .then(Commands.literal("resume").requires(source -> staffPermission(source.getSender(), Permissions.WARP_PAYMENTS_ACKNOWLEDGE))
                    .executes(context -> paymentAcknowledgement(context.getSource().getSender(), null, null)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(context -> warpStatus(context.getSource().getSender(), true, IntegerArgumentType.getInteger(context, "page")))))
            .build());
        SuggestionProvider<CommandSourceStack> staffSuggestions = (context, builder) -> {
            WarpService service = warpServiceSupplier.get();
            if (service != null && configSupplier.get().enabled()) {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Warp warp : service.getAllWarps()) if (warp.name().toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(warp.name());
            }
            return builder.buildFuture();
        };
        commands.register(Commands.literal("warprecover")
            .requires(source -> staffPermission(source.getSender(), Permissions.WARP_RECOVER))
            .executes(context -> staffUsage(context.getSource().getSender()))
            .then(Commands.literal("list").executes(context -> recoverWarp(context.getSource().getSender(), null, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context -> recoverWarp(context.getSource().getSender(), null, IntegerArgumentType.getInteger(context, "page")))))
            .then(Commands.argument("entry-id", StringArgumentType.word()).executes(context -> recoverWarp(context.getSource().getSender(), StringArgumentType.getString(context, "entry-id"), 1)))
            .build());
        commands.register(Commands.literal("warpinfo")
            .requires(source -> staffPermission(source.getSender(), Permissions.WARP_INFO))
            .executes(context -> staffUsage(context.getSource().getSender()))
            .then(Commands.argument("name", StringArgumentType.word()).suggests(staffSuggestions)
                .executes(context -> warpInfo(context.getSource().getSender(), StringArgumentType.getString(context, "name"))))
            .build());
        commands.register(Commands.literal("editwarp")
            .requires(source -> anyEditPermission(source.getSender()))
            .executes(context -> staffUsage(context.getSource().getSender()))
            .then(Commands.argument("name", StringArgumentType.word()).suggests(staffSuggestions)
                .executes(context -> warpInfo(context.getSource().getSender(), StringArgumentType.getString(context, "name")))
                .then(Commands.literal("rename").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_RENAME))
                    .then(Commands.argument("new-name", StringArgumentType.word())
                        .executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"),
                            new WarpEdit.Rename(StringArgumentType.getString(context, "new-name")), Permissions.WARP_EDIT_RENAME))))
                .then(Commands.literal("relocate").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_RELOCATE))
                    .executes(context -> relocateWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"))))
                .then(Commands.literal("category").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_CATEGORY))
                    .then(Commands.argument("category", StringArgumentType.word())
                        .executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"),
                            new WarpEdit.Category(StringArgumentType.getString(context, "category")), Permissions.WARP_EDIT_CATEGORY))))
                .then(Commands.literal("password").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_PASSWORD))
                    .then(Commands.literal("set").then(Commands.argument("password", StringArgumentType.string())
                        .executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"),
                            new WarpEdit.Password(StringArgumentType.getString(context, "password")), Permissions.WARP_EDIT_PASSWORD))))
                    .then(Commands.literal("clear").executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"),
                        new WarpEdit.ClearPassword(), Permissions.WARP_EDIT_PASSWORD))))
                .then(Commands.literal("permission").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_PERMISSION))
                    .then(Commands.literal("required").executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"),
                        new WarpEdit.Permission(true), Permissions.WARP_EDIT_PERMISSION)))
                    .then(Commands.literal("public").executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"),
                        new WarpEdit.Permission(false), Permissions.WARP_EDIT_PERMISSION))))
                .then(Commands.literal("close").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_AVAILABILITY))
                    .executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"),
                        new WarpEdit.Close(configSupplier.get().controlMessages().defaultClosureReason()), Permissions.WARP_EDIT_AVAILABILITY))
                    .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(context -> editWarp(context.getSource().getSender(),
                        StringArgumentType.getString(context, "name"), new WarpEdit.Close(StringArgumentType.getString(context, "reason")), Permissions.WARP_EDIT_AVAILABILITY))))
                .then(Commands.literal("open").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_AVAILABILITY))
                    .executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"), new WarpEdit.Open(), Permissions.WARP_EDIT_AVAILABILITY)))
                .then(Commands.literal("unlisted").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_VISIBILITY))
                    .executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"), new WarpEdit.Listing(true), Permissions.WARP_EDIT_VISIBILITY)))
                .then(Commands.literal("listed").requires(source -> staffPermission(source.getSender(), Permissions.WARP_EDIT_VISIBILITY))
                    .executes(context -> editWarp(context.getSource().getSender(), StringArgumentType.getString(context, "name"), new WarpEdit.Listing(false), Permissions.WARP_EDIT_VISIBILITY))))
            .build());
    }

    private boolean publiclyVisible(CommandSender sender, Warp warp) {
        return WarpBrowsing.visible(sender, warp);
    }

    private int staffUsage(CommandSender sender) {
        sendMessage(sender, configSupplier.get().enabled() ? configSupplier.get().editMessages().usage() : configSupplier.get().messages().moduleDisabled());
        if (configSupplier.get().enabled()) sendMessage(sender, configSupplier.get().controlMessages().usage());
        return Command.SINGLE_SUCCESS;
    }

    private int warpInfo(CommandSender sender, String name) {
        WarpConfig settings = configSupplier.get();
        WarpService service = warpServiceSupplier.get();
        if (!settings.enabled()) sendDisabledMessage(sender);
        else if (!staffPermission(sender, Permissions.WARP_INFO)) sendMessage(sender, settings.messages().noPermission(), Placeholder.unparsed("warp", name));
        else if (service == null || !service.isReady()) sendMessage(sender, settings.messages().storageNotReady());
        else {
            Warp warp = service.getWarp(name).orElse(null);
            if (warp == null) sendMessage(sender, settings.messages().warpNotFound(), Placeholder.unparsed("warp", name));
            else { sendMessage(sender, settings.editMessages().info(),
                Placeholder.unparsed("warp", warp.name()), Placeholder.unparsed("world", warp.worldName()),
                Placeholder.unparsed("world-id", String.valueOf(warp.worldId())),
                Placeholder.unparsed("x", Double.toString(warp.x())), Placeholder.unparsed("y", Double.toString(warp.y())), Placeholder.unparsed("z", Double.toString(warp.z())),
                Placeholder.unparsed("yaw", Float.toString(warp.yaw())), Placeholder.unparsed("pitch", Float.toString(warp.pitch())),
                Placeholder.unparsed("category", warp.category()), Placeholder.unparsed("password-protected", Boolean.toString(warp.hasPassword())),
                Placeholder.unparsed("permission-required", Boolean.toString(warp.permissionGated())),
                Placeholder.unparsed("permission", "tpcore.warp." + warp.name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("creator", String.valueOf(warp.creatorUuid())), Placeholder.unparsed("created-at", Long.toString(warp.createdAt())));
                sendMessage(sender, settings.controlMessages().infoState(), Placeholder.unparsed("closed", Boolean.toString(warp.closed())),
                    Placeholder.unparsed("unlisted", Boolean.toString(warp.unlisted())), Placeholder.unparsed("reason", warp.closureReason()));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int relocateWarp(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) { sendOnlyPlayersMessage(sender); return Command.SINGLE_SUCCESS; }
        if (!staffPermission(sender, Permissions.WARP_EDIT_RELOCATE)) { sendMessage(sender, configSupplier.get().messages().noPermission(), Placeholder.unparsed("warp", name)); return Command.SINGLE_SUCCESS; }
        WarpService service = warpServiceSupplier.get();
        if (service == null) { sendMessage(sender, configSupplier.get().messages().storageNotReady()); return Command.SINGLE_SUCCESS; }
        Location location = player.getLocation();
        WarpResultStatus allowed = service.checkCreation(player);
        if (allowed != WarpResultStatus.SUCCESS) { sendMessage(sender, controlResult(configSupplier.get(), allowed)); return Command.SINGLE_SUCCESS; }
        return editWarp(sender, name, new WarpEdit.Relocate(location.getWorld().getUID(), location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()), Permissions.WARP_EDIT_RELOCATE);
    }

    private int editWarp(CommandSender sender, String name, WarpEdit edit, String permission) {
        WarpConfig settings = configSupplier.get();
        WarpService service = warpServiceSupplier.get();
        if (!settings.enabled()) { sendDisabledMessage(sender); return Command.SINGLE_SUCCESS; }
        if (!staffPermission(sender, permission)) { sendMessage(sender, settings.messages().noPermission(), Placeholder.unparsed("warp", name)); return Command.SINGLE_SUCCESS; }
        if (service == null) { sendMessage(sender, settings.messages().storageNotReady()); return Command.SINGLE_SUCCESS; }
        UUID replyId = sender instanceof Player player ? player.getUniqueId() : null;
        CommandSender console = replyId == null ? sender : null;
        String updatedName = edit instanceof WarpEdit.Rename rename ? rename.name() : name;
        boolean rename = edit instanceof WarpEdit.Rename;
        service.editWarp(name, edit).thenAccept(status -> {
            String message = switch (status) {
                case SUCCESS -> rename ? settings.editMessages().renameSuccess() : settings.editMessages().success();
                case ALREADY_EXISTS -> settings.editMessages().renameCollision();
                case WARP_NOT_FOUND -> settings.messages().warpNotFound();
                case INVALID_NAME -> settings.messages().invalidWarpName();
                case INVALID_VALUE -> settings.editMessages().invalidValue();
                case BUSY -> settings.messages().operationBusy();
                case NOT_READY -> settings.messages().storageNotReady();
                case WORLD_RESTRICTED -> settings.policies().messages().worldRestricted();
                case PROTECTION_DENIED -> settings.policies().messages().protectionDenied();
                default -> settings.messages().operationFailed();
            };
            sendReply(replyId, console, message, Placeholder.unparsed("warp", status == WarpResultStatus.WARP_NOT_FOUND ? name : updatedName), Placeholder.unparsed("old-name", name),
                Placeholder.unparsed("old-permission", "tpcore.warp." + name.toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("permission", "tpcore.warp." + updatedName.toLowerCase(Locale.ROOT)));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int executeWarp(CommandSender sender, String warpName, String rawPassword) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendMessage(sender, config.messages().storageNotReady());
            return Command.SINGLE_SUCCESS;
        }

        if (!player.hasPermission(Permissions.WARP_USE)) { sendMessage(sender, config.messages().noPermission(), Placeholder.unparsed("warp", warpName)); return Command.SINGLE_SUCCESS; }
        UUID playerId = player.getUniqueId();
        double quotedCost = service.getWarp(warpName).map(warp -> config.policies().resolve(warp.name(), warp.category(), config).price()).orElse(config.warpCost());
        service.teleportToWarp(player, warpName, rawPassword).thenAccept(status -> {
            if (status == WarpResultStatus.WARMUP_CANCELLED_MOVE || status == WarpResultStatus.WARMUP_CANCELLED_DAMAGE) return;
            String rawMsg = switch (status) {
                case SUCCESS -> config.messages().teleportSuccess();
                case WARP_NOT_FOUND -> config.messages().warpNotFound();
                case WARP_CLOSED -> config.controlMessages().closed();
                case WARP_CHANGED -> config.controlMessages().changed();
                case PASSWORD_REQUIRED -> config.messages().passwordRequired();
                case INVALID_PASSWORD -> config.messages().invalidPassword();
                case NO_PERMISSION -> config.messages().noPermission();
                case WORLD_NOT_LOADED -> config.messages().worldNotLoaded();
                case UNSAFE_LOCATION -> config.messages().unsafeLocation();
                case WORLD_RESTRICTED -> config.policies().messages().worldRestricted();
                case COMBAT_TAGGED -> config.policies().messages().combatTagged();
                case PROTECTION_DENIED -> config.policies().messages().protectionDenied();
                case COOLDOWN_ACTIVE -> config.messages().cooldownActive();
                case WARMUP_ALREADY_ACTIVE -> config.messages().warmupAlreadyActive();
                case BUSY -> config.messages().operationBusy();
                case NOT_READY -> config.messages().storageNotReady();
                case ECONOMY_UNAVAILABLE -> config.messages().economyUnavailable();
                case INSUFFICIENT_FUNDS -> config.messages().insufficientFunds();
                default -> config.messages().operationFailed();
            };

            long remainingSecs = service.getRemainingCooldownSeconds(playerId);
            String worldName = service.getWarp(warpName).map(Warp::worldName).orElse("unknown");

            sendPlayerReply(
                playerId,
                rawMsg,
                Placeholder.unparsed("warp", warpName),
                Placeholder.unparsed("seconds", String.valueOf(remainingSecs)),
                Placeholder.unparsed("world", worldName),
                Placeholder.unparsed("cost", Double.toString(quotedCost)),
                Placeholder.unparsed("balance", config.messages().diagnostics().unknown()),
                Placeholder.unparsed("reason", service.getWarp(warpName).map(Warp::closureReason).orElse(config.controlMessages().defaultClosureReason()))
            );
        });

        return Command.SINGLE_SUCCESS;
    }

    private int executeSetWarp(CommandSender sender, String warpName, boolean overwrite, String password, String category) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendMessage(sender, config.messages().storageNotReady());
            return Command.SINGLE_SUCCESS;
        }

        UUID playerId = player.getUniqueId();
        service.setWarp(player, warpName, overwrite, password, category).thenAccept(status -> {
            String rawMsg;
            if (status == WarpResultStatus.SUCCESS) {
                rawMsg = config.messages().setWarpSuccess();
            } else if (status == WarpResultStatus.ALREADY_EXISTS) {
                rawMsg = config.messages().setWarpConfirm();
            } else if (status == WarpResultStatus.LIMIT_REACHED) {
                rawMsg = config.messages().limitReached();
            } else if (status == WarpResultStatus.INVALID_NAME) {
                rawMsg = config.messages().invalidWarpName();
            } else if (status == WarpResultStatus.BUSY) {
                rawMsg = config.messages().operationBusy();
            } else if (status == WarpResultStatus.NOT_READY) {
                rawMsg = config.messages().storageNotReady();
            } else if (status == WarpResultStatus.ECONOMY_UNAVAILABLE) {
                rawMsg = config.messages().economyUnavailable();
            } else if (status == WarpResultStatus.INSUFFICIENT_FUNDS) {
                rawMsg = config.messages().insufficientFunds();
            } else if (status == WarpResultStatus.WORLD_RESTRICTED) {
                rawMsg = config.policies().messages().worldRestricted();
            } else if (status == WarpResultStatus.PROTECTION_DENIED) {
                rawMsg = config.policies().messages().protectionDenied();
            } else {
                rawMsg = config.messages().operationFailed();
            }
            sendPlayerReply(playerId, rawMsg, Placeholder.unparsed("warp", warpName),
                Placeholder.unparsed("cost", Double.toString(config.setWarpCost())),
                Placeholder.unparsed("balance", config.messages().diagnostics().unknown()));
        });

        return Command.SINGLE_SUCCESS;
    }

    private int executeDelWarp(CommandSender sender, String warpName, boolean confirm) {
        WarpConfig settings = configSupplier.get();
        WarpService service = warpServiceSupplier.get();
        if (!settings.enabled()) { sendDisabledMessage(sender); return Command.SINGLE_SUCCESS; }
        if (!sender.hasPermission(Permissions.WARP_DEL)) { sendMessage(sender, settings.messages().noPermission(), Placeholder.unparsed("warp", warpName)); return Command.SINGLE_SUCCESS; }
        if (service == null) { sendMessage(sender, settings.messages().storageNotReady()); return Command.SINGLE_SUCCESS; }
        UUID replyId = sender instanceof Player player ? player.getUniqueId() : null;
        CommandSender console = replyId == null ? sender : null;
        UUID actor = replyId == null ? new UUID(0, 0) : replyId;
        CompletableFuture<WarpResultStatus> result = confirm ? service.confirmDeletion(actor, warpName)
            : CompletableFuture.completedFuture(service.prepareDeletion(actor, warpName));
        result.thenAccept(status -> sendReply(replyId, console, controlResult(settings, status), Placeholder.unparsed("warp", warpName),
            Placeholder.unparsed("seconds", Integer.toString(settings.control().deleteConfirmationSeconds()))));
        return Command.SINGLE_SUCCESS;
    }

    private String controlResult(WarpConfig settings, WarpResultStatus status) {
        return switch (status) {
            case SUCCESS -> settings.messages().delWarpSuccess();
            case CONFIRMATION_REQUIRED -> settings.controlMessages().deleteConfirmation();
            case CONFIRMATION_EXPIRED -> settings.controlMessages().confirmationExpired();
            case RECOVERY_NOT_FOUND -> settings.controlMessages().recoveryNotFound();
            case ALREADY_EXISTS -> settings.controlMessages().recoveryConflict();
            case WARP_NOT_FOUND -> settings.messages().warpNotFound();
            case LIMIT_REACHED -> settings.messages().limitReached();
            case BUSY -> settings.messages().operationBusy();
            case NOT_READY -> settings.messages().storageNotReady();
            case WORLD_RESTRICTED -> settings.policies().messages().worldRestricted();
            case COMBAT_TAGGED -> settings.policies().messages().combatTagged();
            case PROTECTION_DENIED -> settings.policies().messages().protectionDenied();
            case WORLD_NOT_LOADED -> settings.messages().worldNotLoaded();
            case UNSAFE_LOCATION -> settings.messages().unsafeLocation();
            case WARP_CLOSED -> settings.controlMessages().closed();
            case WARP_CHANGED -> settings.controlMessages().changed();
            case ECONOMY_UNAVAILABLE -> settings.messages().economyUnavailable();
            case INSUFFICIENT_FUNDS -> settings.messages().insufficientFunds();
            case NO_PERMISSION -> settings.messages().noPermission();
            case PASSWORD_REQUIRED -> settings.messages().passwordRequired();
            case INVALID_PASSWORD -> settings.messages().invalidPassword();
            case COOLDOWN_ACTIVE -> settings.messages().cooldownActive();
            case WARMUP_ALREADY_ACTIVE -> settings.messages().warmupAlreadyActive();
            default -> settings.messages().operationFailed();
        };
    }

    private int recoverWarp(CommandSender sender, String idText, int page) {
        WarpConfig settings = configSupplier.get();
        WarpService service = warpServiceSupplier.get();
        if (!settings.enabled()) { sendDisabledMessage(sender); return Command.SINGLE_SUCCESS; }
        if (!staffPermission(sender, Permissions.WARP_RECOVER)) { sendMessage(sender, settings.messages().noPermission()); return Command.SINGLE_SUCCESS; }
        if (service == null) { sendMessage(sender, settings.messages().storageNotReady()); return Command.SINGLE_SUCCESS; }
        UUID id;
        try { id = idText == null ? null : UUID.fromString(idText); }
        catch (IllegalArgumentException failure) { sendMessage(sender, settings.controlMessages().recoveryNotFound()); return Command.SINGLE_SUCCESS; }
        UUID replyId = sender instanceof Player player ? player.getUniqueId() : null;
        CommandSender console = replyId == null ? sender : null;
        service.getDeletedWarps().thenCompose(entries -> {
            if (id != null) {
                DeletedWarp entry = entries.stream().filter(deleted -> deleted.id().equals(id)).findFirst().orElse(null);
                if (entry == null) { sendReply(replyId, console, settings.controlMessages().recoveryNotFound()); return CompletableFuture.completedFuture(null); }
                return service.restoreWarp(id).thenAccept(status -> sendReply(replyId, console,
                    status == WarpResultStatus.SUCCESS ? settings.controlMessages().recoverySuccess() : controlResult(settings, status),
                    Placeholder.unparsed("warp", entry.warp().name()), Placeholder.unparsed("id", id.toString())));
            }
            if (entries.isEmpty()) sendReply(replyId, console, settings.controlMessages().recoveryEmpty());
            else {
                int size = Math.max(1, Math.min(settings.warpsPerPage(), entries.size()));
                int pages = (entries.size() + size - 1) / size, current = Math.max(1, Math.min(page, pages));
                sendReply(replyId, console, settings.controlMessages().recoveryHeader(), Placeholder.unparsed("page", Integer.toString(current)), Placeholder.unparsed("maxpages", Integer.toString(pages)));
                for (int i = (current - 1) * size; i < Math.min(current * size, entries.size()); i++) {
                    DeletedWarp entry = entries.get(i);
                    sendReply(replyId, console, settings.controlMessages().recoveryEntry(), Placeholder.unparsed("warp", entry.warp().name()),
                        Placeholder.unparsed("id", entry.id().toString()), Placeholder.unparsed("deleted-at", Long.toString(entry.deletedAt())));
                }
            }
            return CompletableFuture.completedFuture(null);
        }).exceptionally(failure -> { sendReply(replyId, console, settings.messages().operationFailed()); return null; });
        return Command.SINGLE_SUCCESS;
    }

    private int executeWarpsList(CommandSender sender, String category, int page) {
        return executeWarpsList(sender, category, null, page);
    }

    private int executeWarpsList(CommandSender sender, String category, String search, int page) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null || !service.isReady()) {
            sendMessage(sender, config.messages().storageNotReady());
            return Command.SINGLE_SUCCESS;
        }

        var result = WarpBrowsing.page(service.getAllWarps(), warp -> WarpBrowsing.visible(sender, warp), new WarpBrowseQuery(category, search, page, false), config.warpsPerPage());
        if (result.total() == 0) { sendMessage(sender, config.messages().warpListEmpty()); return Command.SINGLE_SUCCESS; }
        List<Warp> list = result.warps(); int targetPage = result.query().page(), maxPages = result.pages();
        int startIndex = 0, endIndex = list.size();

        String headerTemplate = search != null ? config.browsing().messages().searchHeader() : (category != null && !category.isBlank())
            ? config.messages().warpListCategoryHeader()
            : config.messages().warpListHeader();

        sendMessage(
            sender,
            headerTemplate,
            Placeholder.unparsed("category", category != null ? category : config.browsing().messages().allCategories()),
            Placeholder.unparsed("search", search == null ? "" : search),
            Placeholder.unparsed("page", String.valueOf(targetPage)),
            Placeholder.unparsed("maxpages", String.valueOf(maxPages))
        );

        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        for (int i = startIndex; i < endIndex; i++) {
            Warp warp = list.get(i);
            Component item = this.miniMessage.deserialize(
                MessageFormatter.toMiniMessage(config.messages().warpListItem()),
                TagResolver.resolver(
                    prefixResolver,
                    Placeholder.unparsed("name", warp.name()),
                    Placeholder.unparsed("category", warp.category()),
                    Placeholder.unparsed("world", warp.worldName()),
                    Placeholder.unparsed("x", String.format("%.1f", warp.x())),
                    Placeholder.unparsed("y", String.format("%.1f", warp.y())),
                    Placeholder.unparsed("z", String.format("%.1f", warp.z())),
                    Placeholder.unparsed("availability", warp.closed() ? config.controlMessages().closedLabel() : config.controlMessages().openLabel()),
                    Placeholder.unparsed("reason", warp.closureReason())
                )
            );
            sender.sendMessage(resolveWarpClickCommands(item, StringArgumentType.escapeIfRequired(warp.name())));
        }

        var messages = config.browsing().messages();
        sendMessage(sender, messages.navigation(), Placeholder.unparsed("page", Integer.toString(targetPage)), Placeholder.unparsed("maxpages", Integer.toString(maxPages)), Placeholder.unparsed("total", Integer.toString(result.total())));
        if (targetPage > 1) sender.sendMessage(browseText(messages.previous(), config).clickEvent(ClickEvent.runCommand(browseCommand(result.query(), targetPage - 1))));
        if (targetPage < maxPages) sender.sendMessage(browseText(messages.next(), config).clickEvent(ClickEvent.runCommand(browseCommand(result.query(), targetPage + 1))));
        sender.sendMessage(browseText(messages.categoriesButton(), config).clickEvent(ClickEvent.runCommand("/warps categories")));
        String searchCommand = "/warps" + (result.query().category().isEmpty() ? "" : " category " + StringArgumentType.escapeIfRequired(result.query().category())) + " search ";
        sender.sendMessage(browseText(messages.searchButton(), config).clickEvent(ClickEvent.suggestCommand(searchCommand)));

        return Command.SINGLE_SUCCESS;
    }

    private int paymentAcknowledgement(CommandSender sender, String ticket, String note) {
        WarpConfig settings = configSupplier.get();
        if (!staffPermission(sender, Permissions.WARP_STATUS) || !staffPermission(sender, Permissions.WARP_PAYMENTS)
            || !staffPermission(sender, Permissions.WARP_PAYMENTS_ACKNOWLEDGE)) { sendMessage(sender, settings.messages().noPermission()); return Command.SINGLE_SUCCESS; }
        WarpService service = warpServiceSupplier.get();
        if (service == null || !settings.enabled()) { sendMessage(sender, settings.messages().storageNotReady()); return Command.SINGLE_SUCCESS; }
        UUID id = sender instanceof Player player ? player.getUniqueId() : null;
        CommandSender console = id == null ? sender : null;
        UUID actor = id == null ? new UUID(0, 0) : id;
        var operation = ticket == null ? service.resumePayments() : service.acknowledgePayment(ticket, actor, note);
        operation.whenComplete((done, failure) -> {
            if (failure == null) {
                plugin.getSLF4JLogger().info("Warp payment administration: actor {}, ticket {}, note {}", actor, ticket, note);
                sendRestrictedReply(id, console, Permissions.WARP_PAYMENTS_ACKNOWLEDGE, settings.payments().messages().get(ticket == null ? "resumed" : "acknowledged"), Placeholder.unparsed("ticket", ticket == null ? "" : ticket));
            } else {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                String reason = settings.payments().messages().getOrDefault("reason-" + String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT), settings.payments().messages().get("reason-storage"));
                plugin.getSLF4JLogger().warn("Warp payment acknowledgement refused for {}", ticket, failure);
                sendRestrictedReply(id, console, Permissions.WARP_PAYMENTS_ACKNOWLEDGE, settings.payments().messages().get("failed"), Placeholder.unparsed("reason", reason));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int warpStatus(CommandSender sender, boolean payments, int page) {
        WarpConfig settings = configSupplier.get();
        if (!staffPermission(sender, Permissions.WARP_STATUS) || (payments && !staffPermission(sender, Permissions.WARP_PAYMENTS))) {
            sendMessage(sender, settings.messages().noPermission());
            return Command.SINGLE_SUCCESS;
        }
        WarpService service = warpServiceSupplier.get();
        if (service == null) { sendMessage(sender, settings.messages().storageNotReady()); return Command.SINGLE_SUCCESS; }
        UUID id = sender instanceof Player player ? player.getUniqueId() : null;
        CommandSender console = id == null ? sender : null;
        var messages = settings.messages().diagnostics();
        if (payments) {
            service.paymentIntents(page, settings.warpsPerPage()).whenComplete((evidence, failure) -> {
                if (failure != null) {
                    plugin.getSLF4JLogger().error("Cannot read warp payment evidence", failure);
                    sendRestrictedReply(id, console, Permissions.WARP_PAYMENTS, messages.failure());
                    return;
                }
                TagResolver[] values = {Placeholder.unparsed("page", Integer.toString(evidence.page())),
                    Placeholder.unparsed("pages", Integer.toString(evidence.pages())), Placeholder.unparsed("total", Integer.toString(evidence.total()))};
                for (String line : messages.paymentHeader()) sendRestrictedReply(id, console, Permissions.WARP_PAYMENTS, line, values);
                if (evidence.entries().isEmpty()) sendRestrictedReply(id, console, Permissions.WARP_PAYMENTS, messages.noPayments());
                for (var entry : evidence.entries()) sendRestrictedReply(id, console, Permissions.WARP_PAYMENTS, messages.paymentEntry(),
                    Placeholder.unparsed("ticket", entry.ticket()), Placeholder.unparsed("record", entry.evidence()));
            });
        } else {
            WarpDiagnostics state = service.diagnostics();
            WarpDiagnostics.Economy economy = state.economy();
            TagResolver[] values = {
                Placeholder.unparsed("storage", state.storage()), Placeholder.unparsed("ready", state.ready() ? messages.yes() : messages.no()),
                Placeholder.unparsed("enabled", settings.enabled() ? messages.yes() : messages.no()),
                Placeholder.unparsed("warps", Integer.toString(state.warps())), Placeholder.unparsed("warp-limit", Integer.toString(state.warpLimit())),
                Placeholder.unparsed("cache-capacity", Integer.toString(state.cacheCapacity())),
                Placeholder.unparsed("pending", Integer.toString(state.pendingOperations())), Placeholder.unparsed("operation-capacity", Integer.toString(state.operationCapacity())),
                Placeholder.unparsed("teleports", Integer.toString(state.activeTeleports())), Placeholder.unparsed("mutations", Integer.toString(state.pendingMutations())),
                Placeholder.unparsed("migrating", state.migrating() ? messages.yes() : messages.no()),
                Placeholder.unparsed("economy", settings.economyEnabled() && economy.available() ? messages.yes() : messages.no()),
                Placeholder.unparsed("journal-ready", economy.journalReady() ? messages.yes() : messages.no()),
                Placeholder.unparsed("reconciliation", economy.reconciliationRequired() ? messages.yes() : messages.no()),
                Placeholder.unparsed("payment-operations", Integer.toString(economy.activeOperations())),
                Placeholder.unparsed("intents", economy.retainedIntents() < 0 ? messages.unknown() : Integer.toString(economy.retainedIntents())),
                Placeholder.unparsed("intent-capacity", Integer.toString(economy.intentCapacity()))};
            for (String line : messages.status()) sendRestrictedReply(id, console, Permissions.WARP_STATUS, line, values);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeWarpOther(CommandSender sender, String targetName, String warpName) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return Command.SINGLE_SUCCESS;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sendMessage(
                sender,
                config.messages().playerNotOnline(),
                Placeholder.unparsed("target", targetName)
            );
            return Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendMessage(sender, config.messages().storageNotReady());
            return Command.SINGLE_SUCCESS;
        }

        String senderName = sender instanceof Player p ? p.getName() : "Console";
        UUID replyId = sender instanceof Player p ? p.getUniqueId() : null;
        CommandSender console = replyId == null ? sender : null;
        UUID targetId = target.getUniqueId();
        service.teleportOtherToWarp(sender instanceof Player p ? p : null, target, warpName).thenAccept(status -> {
            String rawMsg = switch (status) {
                case SUCCESS -> config.messages().teleportOtherSuccess();
                case WARP_CLOSED -> config.controlMessages().closed();
                case WARP_CHANGED -> config.controlMessages().changed();
                default -> controlResult(config, status);
            };
            sendReply(
                replyId, console,
                rawMsg,
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("world", service.getWarp(warpName).map(Warp::worldName).orElse(config.messages().diagnostics().unknown())),
                Placeholder.unparsed("reason", service.getWarp(warpName).map(Warp::closureReason).orElse(config.controlMessages().defaultClosureReason())),
                Placeholder.unparsed("warp", warpName)
            );

            if (status == WarpResultStatus.SUCCESS) {
                sendPlayerReply(
                    targetId,
                    config.messages().teleportedByOther(),
                    Placeholder.unparsed("sender", senderName),
                    Placeholder.unparsed("warp", warpName)
                );
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private void sendOnlyPlayersMessage(CommandSender sender) {
        WarpConfig config = configSupplier.get();
        sendMessage(sender, config.messages().onlyPlayers());
    }

    private void sendDisabledMessage(CommandSender sender) {
        WarpConfig config = configSupplier.get();
        sendMessage(sender, config.messages().moduleDisabled());
    }

    /** MiniMessage does not expand component placeholders inside command arguments. */
    private Component resolveWarpClickCommands(Component component, String warpArgument) {
        ClickEvent event = component.clickEvent();
        if (WARP_RUN_TEMPLATE.equals(event)) component = component.clickEvent(ClickEvent.runCommand("/warp " + warpArgument));
        else if (WARP_SUGGEST_TEMPLATE.equals(event)) component = component.clickEvent(ClickEvent.suggestCommand("/warp " + warpArgument));
        if (component.children().isEmpty()) return component;
        return component.children(component.children().stream().map(child -> resolveWarpClickCommands(child, warpArgument)).toList());
    }

    private void sendMessage(CommandSender sender, String template, TagResolver... customResolvers) {
        if (sender == null || template == null || template.isBlank()) {
            return;
        }
        UUID id = sender instanceof Player player ? player.getUniqueId() : null;
        sendReply(id, id == null ? sender : null, template, customResolvers);
    }

    private void sendReply(UUID id, CommandSender console, String template, TagResolver... resolvers) {
        if (id != null) sendPlayerReply(id, template, resolvers);
        else Bukkit.getGlobalRegionScheduler().execute(plugin, () -> sendDirectMessage(console, template, resolvers));
    }

    private void sendPlayerReply(UUID id, String template, TagResolver... resolvers) {
        sendPlayerReply(id, null, template, resolvers);
    }

    private void sendRestrictedReply(UUID id, CommandSender console, String permission, String template, TagResolver... resolvers) {
        if (id != null) sendPlayerReply(id, permission, template, resolvers);
        else Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (staffPermission(console, Permissions.WARP_STATUS) && staffPermission(console, permission)) sendDirectMessage(console, template, resolvers);
        });
    }

    private void sendPlayerReply(UUID id, String permission, String template, TagResolver... resolvers) {
        Player handle = Bukkit.getPlayer(id);
        if (handle == null) return;
        handle.getScheduler().run(plugin, ignored -> {
            Player current = Bukkit.getPlayer(id);
            if (current != null && (permission == null || (staffPermission(current, Permissions.WARP_STATUS)
                && staffPermission(current, permission)))) sendDirectMessage(current, template, resolvers);
        }, null);
    }

    private void sendDirectMessage(CommandSender sender, String template, TagResolver... customResolvers) {
        WarpConfig config = configSupplier.get();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        TagResolver combined;
        if (customResolvers == null || customResolvers.length == 0) {
            combined = prefixResolver;
        } else {
            TagResolver[] all = new TagResolver[customResolvers.length + 1];
            all[0] = prefixResolver;
            System.arraycopy(customResolvers, 0, all, 1, customResolvers.length);
            combined = TagResolver.resolver(all);
        }
        sender.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
    }
}
