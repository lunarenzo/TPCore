package com.lunatech.tpcore.module.warp.command;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class WarpCommandRegistry {

    private final JavaPlugin plugin;
    private final WarpService warpService;
    private final Supplier<WarpConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public WarpCommandRegistry(JavaPlugin plugin, WarpService warpService, Supplier<WarpConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.warpService = Objects.requireNonNull(warpService, "warpService cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            SuggestionProvider<CommandSourceStack> warpSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                String remaining = builder.getRemaining().toLowerCase();
                for (Warp warp : warpService.getAllWarps()) {
                    if (warp.name().toLowerCase().startsWith(remaining)) {
                        builder.suggest(warp.name());
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> categorySuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                String remaining = builder.getRemaining().toLowerCase();
                for (String category : warpService.getCategories()) {
                    if (category.toLowerCase().startsWith(remaining)) {
                        builder.suggest(category);
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> playerSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                String remaining = builder.getRemaining().toLowerCase();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(remaining)) {
                        builder.suggest(player.getName());
                    }
                }
                return builder.buildFuture();
            };

            // /warp <name> [password]
            commands.register(
                Commands.literal("warp")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_USE))
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

            // /setwarp <name> [category] [password]
            commands.register(
                Commands.literal("setwarp")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_SET))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(warpSuggestions)
                        .executes(ctx -> executeSetWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), false, null, null))
                        .then(Commands.literal("-f")
                            .executes(ctx -> executeSetWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), true, null, null))
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
                            .then(Commands.argument("password", StringArgumentType.string())
                                .executes(ctx -> executeSetWarp(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "name"),
                                    false,
                                    StringArgumentType.getString(ctx, "password"),
                                    StringArgumentType.getString(ctx, "category")
                                ))
                            )
                        )
                    )
                    .build(),
                "Set a server warp location",
                List.of("createwarp")
            );

            // /delwarp <name>
            commands.register(
                Commands.literal("delwarp")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_DEL))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(warpSuggestions)
                        .executes(ctx -> executeDelWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name")))
                    )
                    .build(),
                "Delete a server warp location",
                List.of("removewarp")
            );

            // /warps [category] [page]
            commands.register(
                Commands.literal("warps")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_LIST))
                    .executes(ctx -> executeWarpsList(ctx.getSource().getSender(), null, 1))
                    .then(Commands.argument("category", StringArgumentType.word())
                        .suggests(categorySuggestions)
                        .executes(ctx -> executeWarpsList(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "category"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> executeWarpsList(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "category"),
                                IntegerArgumentType.getInteger(ctx, "page")
                            ))
                        )
                    )
                    .build(),
                "List available server warps",
                List.of("listwarps", "warplist")
            );

            // /warpother <target> <name>
            commands.register(
                Commands.literal("warpother")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_OTHER))
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
        });
    }

    private int executeWarp(CommandSender sender, String warpName, String rawPassword) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        warpService.teleportToWarp(player, warpName, rawPassword).thenAccept(status -> {
            String rawMsg = switch (status) {
                case SUCCESS -> config.messages().prefix() + config.messages().teleportSuccess();
                case WARP_NOT_FOUND -> config.messages().prefix() + config.messages().warpNotFound();
                case PASSWORD_REQUIRED -> config.messages().prefix() + config.messages().passwordRequired();
                case INVALID_PASSWORD -> config.messages().prefix() + config.messages().invalidPassword();
                case NO_PERMISSION -> config.messages().prefix() + config.messages().noPermission();
                case WORLD_NOT_LOADED -> config.messages().prefix() + config.messages().worldNotLoaded();
                case UNSAFE_LOCATION -> config.messages().prefix() + config.messages().unsafeLocation();
                case COOLDOWN_ACTIVE -> config.messages().prefix() + config.messages().cooldownActive();
                default -> config.messages().prefix() + config.messages().warpNotFound();
            };
            player.sendMessage(miniMessage.deserialize(rawMsg, Placeholder.unparsed("warp", warpName)));
        });

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeSetWarp(CommandSender sender, String warpName, boolean overwrite, String password, String category) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        warpService.setWarp(player, warpName, overwrite, password, category).thenAccept(status -> {
            String rawMsg = (status == WarpResultStatus.SUCCESS)
                ? config.messages().prefix() + config.messages().setWarpSuccess()
                : config.messages().prefix() + config.messages().setWarpConfirm();
            player.sendMessage(miniMessage.deserialize(rawMsg, Placeholder.unparsed("warp", warpName)));
        });

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeDelWarp(CommandSender sender, String warpName) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        warpService.deleteWarp(warpName).thenAccept(status -> {
            String rawMsg = (status == WarpResultStatus.SUCCESS)
                ? config.messages().prefix() + config.messages().delWarpSuccess()
                : config.messages().prefix() + config.messages().warpNotFound();
            sender.sendMessage(miniMessage.deserialize(rawMsg, Placeholder.unparsed("warp", warpName)));
        });

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeWarpsList(CommandSender sender, String category, int page) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        Collection<Warp> rawList = (category != null && !category.isBlank())
            ? warpService.getWarpsByCategory(category)
            : warpService.getAllWarps();

        if (rawList.isEmpty()) {
            sender.sendMessage(miniMessage.deserialize(config.messages().prefix() + config.messages().warpListEmpty()));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        List<Warp> list = new ArrayList<>(rawList);
        int perPage = Math.max(1, config.warpsPerPage());
        int maxPages = (int) Math.ceil((double) list.size() / perPage);
        int targetPage = Math.min(Math.max(1, page), maxPages);

        int startIndex = (targetPage - 1) * perPage;
        int endIndex = Math.min(startIndex + perPage, list.size());

        String headerTemplate = (category != null && !category.isBlank())
            ? config.messages().warpListCategoryHeader()
            : config.messages().warpListHeader();

        sender.sendMessage(miniMessage.deserialize(
            config.messages().prefix() + headerTemplate,
            Placeholder.unparsed("category", category != null ? category : "All"),
            Placeholder.unparsed("page", String.valueOf(targetPage)),
            Placeholder.unparsed("maxpages", String.valueOf(maxPages))
        ));

        for (int i = startIndex; i < endIndex; i++) {
            Warp warp = list.get(i);
            Component item = miniMessage.deserialize(
                config.messages().warpListItem(),
                Placeholder.unparsed("name", warp.name()),
                Placeholder.unparsed("category", warp.category()),
                Placeholder.unparsed("world", warp.worldName()),
                Placeholder.unparsed("x", String.format("%.1f", warp.x())),
                Placeholder.unparsed("y", String.format("%.1f", warp.y())),
                Placeholder.unparsed("z", String.format("%.1f", warp.z()))
            );
            sender.sendMessage(item);
        }

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeWarpOther(CommandSender sender, String targetName, String warpName) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(miniMessage.deserialize(
                config.messages().prefix() + config.messages().warpNotFound(),
                Placeholder.unparsed("warp", warpName)
            ));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        warpService.teleportOtherToWarp(sender instanceof Player p ? p : null, target, warpName).thenAccept(status -> {
            String rawMsg = (status == WarpResultStatus.SUCCESS)
                ? config.messages().prefix() + config.messages().teleportOtherSuccess()
                : config.messages().prefix() + config.messages().warpNotFound();
            sender.sendMessage(miniMessage.deserialize(
                rawMsg,
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("warp", warpName)
            ));
        });

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void sendOnlyPlayersMessage(CommandSender sender) {
        WarpConfig config = configSupplier.get();
        sender.sendMessage(miniMessage.deserialize(
            config.messages().onlyPlayers(),
            Placeholder.unparsed("prefix", config.messages().prefix())
        ));
    }

    private void sendDisabledMessage(CommandSender sender) {
        WarpConfig config = configSupplier.get();
        sender.sendMessage(miniMessage.deserialize(
            config.messages().prefix() + config.messages().moduleDisabled()
        ));
    }
}
