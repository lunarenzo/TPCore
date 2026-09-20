package com.lunatech.tpcore.module.pwarp.command;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.pwarp.config.PwarpConfig;
import com.lunatech.tpcore.module.pwarp.gui.PwarpGuiManager;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.model.PwarpCategory;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Brigadier Command Suite for the PlayerWarps module (/pwarp, /setpwarp, /delpwarp, /pwarps).
 */
public final class PwarpCommandRegistry {

    private final JavaPlugin plugin;
    private final PwarpService pwarpService;
    private final PwarpGuiManager guiManager;
    private final Supplier<PwarpConfig> configSupplier;
    private final Supplier<Boolean> reloadAction;
    private final MiniMessage miniMessage;

    public PwarpCommandRegistry(
        JavaPlugin plugin,
        PwarpService pwarpService,
        PwarpGuiManager guiManager,
        Supplier<PwarpConfig> configSupplier,
        Supplier<Boolean> reloadAction
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.pwarpService = Objects.requireNonNull(pwarpService, "pwarpService cannot be null");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction cannot be null");
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            SuggestionProvider<CommandSourceStack> publicWarpSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                String remaining = builder.getRemaining().toLowerCase();
                for (Pwarp warp : pwarpService.getPublicWarps()) {
                    if (warp.name().toLowerCase().startsWith(remaining)) {
                        builder.suggest(warp.name());
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> ownedWarpSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                CommandSender sender = context.getSource().getSender();
                String remaining = builder.getRemaining().toLowerCase();
                if (sender.hasPermission(Permissions.PWARP_ADMIN)) {
                    for (Pwarp warp : pwarpService.getPublicWarps()) {
                        if (warp.name().toLowerCase().startsWith(remaining)) {
                            builder.suggest(warp.name());
                        }
                    }
                } else if (sender instanceof Player player) {
                    for (Pwarp warp : pwarpService.getPlayerWarps(player.getUniqueId())) {
                        if (warp.name().toLowerCase().startsWith(remaining)) {
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
                String remaining = builder.getRemaining().toLowerCase();
                for (PwarpCategory cat : configSupplier.get().categories()) {
                    if (cat.key().toLowerCase().startsWith(remaining)) {
                        builder.suggest(cat.key());
                    }
                }
                return builder.buildFuture();
            };

            // Main /pwarp command
            commands.register(
                Commands.literal("pwarp")
                    .requires(src -> src.getSender().hasPermission(Permissions.PWARP_USE))
                    .executes(ctx -> handlePwarpOrGui(ctx.getSource().getSender(), null))
                    .then(Commands.literal("gui")
                        .executes(ctx -> handleGuiCommand(ctx.getSource().getSender()))
                    )
                    .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission(Permissions.PWARP_ADMIN))
                        .executes(ctx -> handleReloadCommand(ctx.getSource().getSender()))
                    )
                    .then(Commands.literal("rate")
                        .requires(src -> src.getSender().hasPermission(Permissions.PWARP_USE))
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(publicWarpSuggestions)
                            .executes(ctx -> handleRateGuiCommand(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "name")
                            ))
                            .then(Commands.argument("stars", IntegerArgumentType.integer(1, 5))
                                .executes(ctx -> handleRateCommand(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "name"),
                                    IntegerArgumentType.getInteger(ctx, "stars")
                                ))
                            )
                        )
                    )
                    .then(Commands.literal("set")
                        .requires(src -> src.getSender().hasPermission(Permissions.PWARP_SET))
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(ctx -> handleSetCommand(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "name"),
                                "general",
                                null
                            ))
                            .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(categorySuggestions)
                                .executes(ctx -> handleSetCommand(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "category"),
                                    null
                                ))
                                .then(Commands.argument("description", StringArgumentType.greedyString())
                                    .executes(ctx -> handleSetCommand(
                                        ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "name"),
                                        StringArgumentType.getString(ctx, "category"),
                                        StringArgumentType.getString(ctx, "description")
                                    ))
                                )
                            )
                        )
                    )
                    .then(Commands.literal("del")
                        .requires(src -> src.getSender().hasPermission(Permissions.PWARP_DEL))
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(ownedWarpSuggestions)
                            .executes(ctx -> handleDelCommand(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "name")
                            ))
                        )
                    )
                    .then(Commands.literal("tp")
                        .requires(src -> src.getSender().hasPermission(Permissions.PWARP_USE))
                        .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(publicWarpSuggestions)
                            .executes(ctx -> handlePwarpOrGui(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "name")
                            ))
                        )
                    )
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(publicWarpSuggestions)
                        .executes(ctx -> handlePwarpOrGui(
                            ctx.getSource().getSender(),
                            StringArgumentType.getString(ctx, "name")
                        ))
                    )
                    .build(),
                "Access or manage player warps",
                List.of("pw")
            );

            // Standalone /setpwarp <name> [category] [description]
            commands.register(
                Commands.literal("setpwarp")
                    .requires(src -> src.getSender().hasPermission(Permissions.PWARP_SET))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> handleSetCommand(
                            ctx.getSource().getSender(),
                            StringArgumentType.getString(ctx, "name"),
                            "general",
                            null
                        ))
                        .then(Commands.argument("category", StringArgumentType.word())
                            .suggests(categorySuggestions)
                            .executes(ctx -> handleSetCommand(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "category"),
                                null
                            ))
                            .then(Commands.argument("description", StringArgumentType.greedyString())
                                .executes(ctx -> handleSetCommand(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "category"),
                                    StringArgumentType.getString(ctx, "description")
                                ))
                            )
                        )
                    )
                    .build(),
                "Set a player warp at your current location",
                List.of()
            );

            // Standalone /delpwarp <name>
            commands.register(
                Commands.literal("delpwarp")
                    .requires(src -> src.getSender().hasPermission(Permissions.PWARP_DEL))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(ownedWarpSuggestions)
                        .executes(ctx -> handleDelCommand(
                            ctx.getSource().getSender(),
                            StringArgumentType.getString(ctx, "name")
                        ))
                    )
                    .build(),
                "Delete one of your player warps",
                List.of()
            );

            // Standalone /pwarps
            commands.register(
                Commands.literal("pwarps")
                    .requires(src -> src.getSender().hasPermission(Permissions.PWARP_USE))
                    .executes(ctx -> handleGuiCommand(ctx.getSource().getSender()))
                    .build(),
                "Open the player warps menu",
                List.of()
            );
        });
    }

    private int handlePwarpOrGui(CommandSender sender, String warpName) {
        if (!configSupplier.get().enabled()) {
            sendConfigMessage(sender, configSupplier.get().messages().disabled());
            return 0;
        }

        if (!(sender instanceof Player player)) {
            sendConfigMessage(sender, configSupplier.get().messages().onlyPlayers());
            return 0;
        }

        if (warpName == null || warpName.isBlank()) {
            this.guiManager.openWarpsGui(player, 0);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        this.pwarpService.executeTeleport(player, warpName);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int handleGuiCommand(CommandSender sender) {
        if (!configSupplier.get().enabled()) {
            sendConfigMessage(sender, configSupplier.get().messages().disabled());
            return 0;
        }

        if (!(sender instanceof Player player)) {
            sendConfigMessage(sender, configSupplier.get().messages().onlyPlayers());
            return 0;
        }

        this.guiManager.openWarpsGui(player, 0);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int handleRateGuiCommand(CommandSender sender, String warpName) {
        if (!configSupplier.get().enabled()) {
            sendConfigMessage(sender, configSupplier.get().messages().disabled());
            return 0;
        }

        if (!(sender instanceof Player player)) {
            sendConfigMessage(sender, configSupplier.get().messages().onlyPlayers());
            return 0;
        }

        Optional<Pwarp> optWarp = this.pwarpService.getWarp(warpName);
        if (optWarp.isEmpty()) {
            sendConfigMessage(sender, configSupplier.get().messages().warpNotFound());
            return 0;
        }

        this.guiManager.openRateWarpGui(player, optWarp.get());
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int handleRateCommand(CommandSender sender, String warpName, int stars) {
        if (!configSupplier.get().enabled()) {
            sendConfigMessage(sender, configSupplier.get().messages().disabled());
            return 0;
        }

        if (!(sender instanceof Player player)) {
            sendConfigMessage(sender, configSupplier.get().messages().onlyPlayers());
            return 0;
        }

        this.pwarpService.rateWarp(player, warpName, stars);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int handleSetCommand(CommandSender sender, String warpName, String category, String description) {
        if (!configSupplier.get().enabled()) {
            sendConfigMessage(sender, configSupplier.get().messages().disabled());
            return 0;
        }

        if (!(sender instanceof Player player)) {
            sendConfigMessage(sender, configSupplier.get().messages().onlyPlayers());
            return 0;
        }

        this.pwarpService.setWarp(player, warpName, category, description);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int handleDelCommand(CommandSender sender, String warpName) {
        if (!configSupplier.get().enabled()) {
            sendConfigMessage(sender, configSupplier.get().messages().disabled());
            return 0;
        }

        if (!(sender instanceof Player player)) {
            sendConfigMessage(sender, configSupplier.get().messages().onlyPlayers());
            return 0;
        }

        this.pwarpService.deleteWarp(player, warpName);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int handleReloadCommand(CommandSender sender) {
        boolean success = this.reloadAction.get();
        if (success) {
            sendConfigMessage(sender, configSupplier.get().messages().reloadSuccess());
        } else {
            sender.sendMessage(this.miniMessage.deserialize("<red>Failed to reload PlayerWarps module configuration.</red>"));
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void sendConfigMessage(CommandSender sender, String messageKey) {
        if (messageKey == null || messageKey.isBlank()) {
            return;
        }
        String prefix = configSupplier.get().messages().prefix();
        sender.sendMessage(this.miniMessage.deserialize(prefix + messageKey));
    }
}
