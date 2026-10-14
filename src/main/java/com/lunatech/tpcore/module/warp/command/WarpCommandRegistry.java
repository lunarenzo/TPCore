package com.lunatech.tpcore.module.warp.command;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.model.Warp;
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
import java.util.ArrayList;
import java.util.Collection;
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

    private final JavaPlugin plugin;
    private final Supplier<WarpService> warpServiceSupplier;
    private final Supplier<WarpConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public WarpCommandRegistry(JavaPlugin plugin, Supplier<WarpService> warpServiceSupplier, Supplier<WarpConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.warpServiceSupplier = Objects.requireNonNull(warpServiceSupplier, "warpServiceSupplier cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    public void registerAll() {
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
                    Player player = (sender instanceof Player p) ? p : null;
                    for (Warp warp : service.getAllWarps()) {
                        boolean canSee = !warp.permissionGated()
                            || player == null
                            || player.hasPermission("tpcore.warp." + warp.name().toLowerCase(Locale.ROOT))
                            || player.hasPermission("tpcore.warp.admin");
                        if (canSee && warp.name().toLowerCase(Locale.ROOT).startsWith(remaining)) {
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
                    for (String category : service.getCategories()) {
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
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
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
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
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
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(warpSuggestions)
                        .executes(ctx -> executeDelWarp(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name")))
                    )
                    .build(),
                "Delete a server warp location",
                List.of("removewarp")
            );

            // /warps [category|page] [page]
            commands.register(
                Commands.literal("warps")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_LIST))
                    .executes(ctx -> executeWarpsList(ctx.getSource().getSender(), null, 1))
                    .then(Commands.argument("query", StringArgumentType.word())
                        .suggests(categorySuggestions)
                        .executes(ctx -> {
                            String query = StringArgumentType.getString(ctx, "query");
                            if (query.matches("\\d+")) {
                                int page = Integer.parseInt(query);
                                return executeWarpsList(ctx.getSource().getSender(), null, page);
                            }
                            return executeWarpsList(ctx.getSource().getSender(), query, 1);
                        })
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> executeWarpsList(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "query"),
                                IntegerArgumentType.getInteger(ctx, "page")
                            ))
                        )
                    )
                    .build(),
                "List available server warps",
                List.of("listwarps", "warplist")
            );

            // /warpother [target] [name]
            commands.register(
                Commands.literal("warpother")
                    .requires(src -> src.getSender().hasPermission(Permissions.WARP_OTHER))
                    .executes(ctx -> {
                        sendMessage(ctx.getSource().getSender(), configSupplier.get().messages().warpOtherUsage());
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
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
        });
    }

    private boolean staffPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.hasPermission(Permissions.WARP_ADMIN);
    }

    private boolean anyEditPermission(CommandSender sender) {
        return staffPermission(sender, Permissions.WARP_INFO) || staffPermission(sender, Permissions.WARP_EDIT_RENAME)
            || staffPermission(sender, Permissions.WARP_EDIT_RELOCATE) || staffPermission(sender, Permissions.WARP_EDIT_CATEGORY)
            || staffPermission(sender, Permissions.WARP_EDIT_PASSWORD) || staffPermission(sender, Permissions.WARP_EDIT_PERMISSION);
    }

    private void registerStaffCommands(Commands commands) {
        SuggestionProvider<CommandSourceStack> staffSuggestions = (context, builder) -> {
            WarpService service = warpServiceSupplier.get();
            if (service != null && configSupplier.get().enabled()) {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Warp warp : service.getAllWarps()) if (warp.name().toLowerCase(Locale.ROOT).startsWith(remaining)) builder.suggest(warp.name());
            }
            return builder.buildFuture();
        };
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
                        new WarpEdit.Permission(false), Permissions.WARP_EDIT_PERMISSION)))))
            .build());
    }

    private int staffUsage(CommandSender sender) {
        sendMessage(sender, configSupplier.get().enabled() ? configSupplier.get().editMessages().usage() : configSupplier.get().messages().moduleDisabled());
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
            else sendMessage(sender, settings.editMessages().info(),
                Placeholder.unparsed("warp", warp.name()), Placeholder.unparsed("world", warp.worldName()),
                Placeholder.unparsed("world-id", String.valueOf(warp.worldId())),
                Placeholder.unparsed("x", Double.toString(warp.x())), Placeholder.unparsed("y", Double.toString(warp.y())), Placeholder.unparsed("z", Double.toString(warp.z())),
                Placeholder.unparsed("yaw", Float.toString(warp.yaw())), Placeholder.unparsed("pitch", Float.toString(warp.pitch())),
                Placeholder.unparsed("category", warp.category()), Placeholder.unparsed("password-protected", Boolean.toString(warp.hasPassword())),
                Placeholder.unparsed("permission-required", Boolean.toString(warp.permissionGated())),
                Placeholder.unparsed("permission", "tpcore.warp." + warp.name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("creator", String.valueOf(warp.creatorUuid())), Placeholder.unparsed("created-at", Long.toString(warp.createdAt())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int relocateWarp(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) { sendOnlyPlayersMessage(sender); return Command.SINGLE_SUCCESS; }
        if (!staffPermission(sender, Permissions.WARP_EDIT_RELOCATE)) { sendMessage(sender, configSupplier.get().messages().noPermission(), Placeholder.unparsed("warp", name)); return Command.SINGLE_SUCCESS; }
        Location location = player.getLocation();
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
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        UUID playerId = player.getUniqueId();
        service.teleportToWarp(player, warpName, rawPassword).thenAccept(status -> {
            if (status == WarpResultStatus.WARMUP_CANCELLED_MOVE || status == WarpResultStatus.WARMUP_CANCELLED_DAMAGE) return;
            String rawMsg = switch (status) {
                case SUCCESS -> config.messages().teleportSuccess();
                case WARP_NOT_FOUND -> config.messages().warpNotFound();
                case PASSWORD_REQUIRED -> config.messages().passwordRequired();
                case INVALID_PASSWORD -> config.messages().invalidPassword();
                case NO_PERMISSION -> config.messages().noPermission();
                case WORLD_NOT_LOADED -> config.messages().worldNotLoaded();
                case UNSAFE_LOCATION -> config.messages().unsafeLocation();
                case COOLDOWN_ACTIVE -> config.messages().cooldownActive();
                case WARMUP_ALREADY_ACTIVE -> config.messages().warmupAlreadyActive();
                case BUSY -> config.messages().operationBusy();
                case NOT_READY -> config.messages().storageNotReady();
                case ECONOMY_UNAVAILABLE -> config.messages().economyUnavailable();
                case INSUFFICIENT_FUNDS -> config.messages().operationFailed();
                default -> config.messages().operationFailed();
            };

            long remainingSecs = service.getRemainingCooldownSeconds(playerId);
            String worldName = service.getWarp(warpName).map(Warp::worldName).orElse("unknown");

            sendPlayerReply(
                playerId,
                rawMsg,
                Placeholder.unparsed("warp", warpName),
                Placeholder.unparsed("seconds", String.valueOf(remainingSecs)),
                Placeholder.unparsed("world", worldName)
            );
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

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
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
            } else {
                rawMsg = config.messages().operationFailed();
            }
            sendPlayerReply(playerId, rawMsg, Placeholder.unparsed("warp", warpName));
        });

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeDelWarp(CommandSender sender, String warpName) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        UUID replyId = sender instanceof Player player ? player.getUniqueId() : null;
        CommandSender console = replyId == null ? sender : null;
        service.deleteWarp(warpName).thenAccept(status -> {
            String rawMsg = (status == WarpResultStatus.SUCCESS)
                ? config.messages().delWarpSuccess()
                : config.messages().warpNotFound();
            sendReply(replyId, console, rawMsg, Placeholder.unparsed("warp", warpName));
        });

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeWarpsList(CommandSender sender, String category, int page) {
        WarpConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        Collection<Warp> rawList = (category != null && !category.isBlank())
            ? service.getWarpsByCategory(category)
            : service.getAllWarps();

        Player playerSender = (sender instanceof Player p) ? p : null;
        List<Warp> visibleList = rawList.stream()
            .filter(warp -> !warp.permissionGated()
                || playerSender == null
                || playerSender.hasPermission("tpcore.warp." + warp.name().toLowerCase(Locale.ROOT))
                || playerSender.hasPermission("tpcore.warp.admin"))
            .toList();

        if (visibleList.isEmpty()) {
            sendMessage(sender, config.messages().warpListEmpty());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        List<Warp> list = new ArrayList<>(visibleList);
        int perPage = Math.max(1, config.warpsPerPage());
        int maxPages = Math.max(1, (int) Math.ceil((double) list.size() / perPage));
        int targetPage = Math.max(1, Math.min(page, maxPages));

        int startIndex = (targetPage - 1) * perPage;
        int endIndex = Math.min(startIndex + perPage, list.size());

        String headerTemplate = (category != null && !category.isBlank())
            ? config.messages().warpListCategoryHeader()
            : config.messages().warpListHeader();

        sendMessage(
            sender,
            headerTemplate,
            Placeholder.unparsed("category", category != null ? category : "All"),
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
                    Placeholder.unparsed("z", String.format("%.1f", warp.z()))
                )
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

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sendMessage(
                sender,
                config.messages().playerNotOnline(),
                Placeholder.unparsed("target", targetName)
            );
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        WarpService service = warpServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        String senderName = sender instanceof Player p ? p.getName() : "Console";
        UUID replyId = sender instanceof Player p ? p.getUniqueId() : null;
        CommandSender console = replyId == null ? sender : null;
        UUID targetId = target.getUniqueId();
        service.teleportOtherToWarp(sender instanceof Player p ? p : null, target, warpName).thenAccept(status -> {
            String rawMsg = (status == WarpResultStatus.SUCCESS)
                ? config.messages().teleportOtherSuccess()
                : config.messages().warpNotFound();
            sendReply(
                replyId, console,
                rawMsg,
                Placeholder.unparsed("target", targetName),
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

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void sendOnlyPlayersMessage(CommandSender sender) {
        WarpConfig config = configSupplier.get();
        sendMessage(sender, config.messages().onlyPlayers());
    }

    private void sendDisabledMessage(CommandSender sender) {
        WarpConfig config = configSupplier.get();
        sendMessage(sender, config.messages().moduleDisabled());
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
        Player handle = Bukkit.getPlayer(id);
        if (handle == null) return;
        handle.getScheduler().run(plugin, ignored -> {
            Player current = Bukkit.getPlayer(id);
            if (current != null) sendDirectMessage(current, template, resolvers);
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
