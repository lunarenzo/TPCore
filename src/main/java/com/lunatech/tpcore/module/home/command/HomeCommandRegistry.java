package com.lunatech.tpcore.module.home.command;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.HomeService;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomeCommandRegistry {

    private final JavaPlugin plugin;
    private final HomeService homeService;
    private final Supplier<HomeConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public HomeCommandRegistry(JavaPlugin plugin, HomeService homeService, Supplier<HomeConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.homeService = Objects.requireNonNull(homeService, "homeService cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            SuggestionProvider<CommandSourceStack> homeNameSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                if (context.getSource().getSender() instanceof Player player) {
                    Map<String, Home> homes = homeService.getHomes(player.getUniqueId());
                    for (String name : homes.keySet()) {
                        if (name.toLowerCase().startsWith(builder.getRemaining().toLowerCase())) {
                            builder.suggest(name);
                        }
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> onlinePlayerSuggestions = (context, builder) -> {
                if (!configSupplier.get().enabled()) {
                    return builder.buildFuture();
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(builder.getRemaining().toLowerCase())) {
                        builder.suggest(player.getName());
                    }
                }
                return builder.buildFuture();
            };

            // /home [name]
            commands.register(
                Commands.literal("home")
                    .requires(src -> src.getSender().hasPermission(Permissions.HOME_USE))
                    .executes(ctx -> executeHome(ctx.getSource().getSender(), null))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(homeNameSuggestions)
                        .executes(ctx -> executeHome(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name")))
                    )
                    .build(),
                "Teleport to your saved home",
                List.of()
            );

            // /sethome [name]
            commands.register(
                Commands.literal("sethome")
                    .requires(src -> src.getSender().hasPermission(Permissions.HOME_SET))
                    .executes(ctx -> executeSetHome(ctx.getSource().getSender(), null, false))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(homeNameSuggestions)
                        .executes(ctx -> executeSetHome(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), false))
                        .then(Commands.literal("-f")
                            .executes(ctx -> executeSetHome(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name"), true))
                        )
                    )
                    .build(),
                "Set a home at your current location",
                List.of()
            );

            // /delhome <name>
            commands.register(
                Commands.literal("delhome")
                    .requires(src -> src.getSender().hasPermission(Permissions.HOME_DEL))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(homeNameSuggestions)
                        .executes(ctx -> executeDelHome(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "name")))
                    )
                    .build(),
                "Delete a saved home",
                List.of("removehome")
            );

            // /homes
            commands.register(
                Commands.literal("homes")
                    .requires(src -> src.getSender().hasPermission(Permissions.HOME_LIST))
                    .executes(ctx -> executeHomesList(ctx.getSource().getSender()))
                    .build(),
                "List your saved homes",
                List.of("listhomes")
            );

            // /homeother <player> <name>
            commands.register(
                Commands.literal("homeother")
                    .requires(src -> src.getSender().hasPermission(Permissions.HOME_OTHER))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(onlinePlayerSuggestions)
                        .then(Commands.argument("name", StringArgumentType.word())
                            .executes(ctx -> executeHomeOther(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "target"),
                                StringArgumentType.getString(ctx, "name")
                            ))
                        )
                    )
                    .build(),
                "Teleport to another player's home",
                List.of()
            );
        });
    }

    private int executeHome(CommandSender sender, String homeName) {
        HomeConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        String targetHome = (homeName != null && !homeName.isBlank()) ? homeName : config.defaultHomeName();

        if (homeService instanceof DefaultHomeService defaultService) {
            long remaining = defaultService.getRemainingCooldownSeconds(player);
            if (remaining > 0 && !player.hasPermission(Permissions.HOME_BYPASS_COOLDOWN)) {
                String msg = config.messages().prefix() + config.messages().cooldownActive();
                player.sendMessage(miniMessage.deserialize(msg, Placeholder.parsed("seconds", String.valueOf(remaining))));
                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
            }
        }

        this.homeService.teleportHome(player, targetHome).thenAccept(success -> {
            if (!success) {
                String msg = config.messages().prefix() + config.messages().homeNotFound();
                player.sendMessage(miniMessage.deserialize(msg, Placeholder.parsed("home", targetHome)));
            }
        });
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeSetHome(CommandSender sender, String homeName, boolean force) {
        HomeConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        String targetHome = (homeName != null && !homeName.isBlank()) ? homeName : config.defaultHomeName();

        this.homeService.setHome(player, targetHome, force).thenAccept(status -> {
            String rawMsg = switch (status) {
                case SUCCESS -> config.messages().prefix() + config.messages().setHomeSuccess();
                case OVERWRITE_REQUIRED -> config.messages().prefix() + config.messages().setHomeConfirm();
                case LIMIT_REACHED -> config.messages().prefix() + config.messages().limitReached();
                case WORLD_RESTRICTED -> config.messages().prefix() + config.messages().worldRestricted();
                default -> config.messages().prefix() + config.messages().homeNotFound();
            };
            player.sendMessage(miniMessage.deserialize(
                rawMsg,
                Placeholder.parsed("home", targetHome),
                Placeholder.parsed("max", String.valueOf(homeService.getMaxHomeLimit(player)))
            ));
        });
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeDelHome(CommandSender sender, String homeName) {
        HomeConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        this.homeService.deleteHome(player, homeName).thenAccept(status -> {
            String rawMsg = (status == HomeResultStatus.SUCCESS)
                ? config.messages().prefix() + config.messages().delHomeSuccess()
                : config.messages().prefix() + config.messages().homeNotFound();
            player.sendMessage(miniMessage.deserialize(rawMsg, Placeholder.parsed("home", homeName)));
        });
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeHomesList(CommandSender sender) {
        HomeConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        Map<String, Home> homes = homeService.getHomes(player.getUniqueId());

        if (homes.isEmpty()) {
            player.sendMessage(miniMessage.deserialize(config.messages().prefix() + config.messages().homeListEmpty()));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        int max = homeService.getMaxHomeLimit(player);
        player.sendMessage(miniMessage.deserialize(
            config.messages().prefix() + config.messages().homeListHeader(),
            Placeholder.parsed("used", String.valueOf(homes.size())),
            Placeholder.parsed("max", max == Integer.MAX_VALUE ? "∞" : String.valueOf(max))
        ));

        homes.values().forEach(home -> {
            Component item = miniMessage.deserialize(
                config.messages().homeListItem(),
                Placeholder.parsed("name", home.name()),
                Placeholder.parsed("world", home.worldName()),
                Placeholder.parsed("x", String.format("%.1f", home.x())),
                Placeholder.parsed("y", String.format("%.1f", home.y())),
                Placeholder.parsed("z", String.format("%.1f", home.z()))
            );
            player.sendMessage(item);
        });

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeHomeOther(CommandSender sender, String targetName, String homeName) {
        HomeConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        this.homeService.teleportHomeOther(player, target.getUniqueId(), homeName).thenAccept(success -> {
            if (!success) {
                String msg = config.messages().prefix() + config.messages().homeNotFoundOther();
                player.sendMessage(miniMessage.deserialize(
                    msg,
                    Placeholder.parsed("target", targetName),
                    Placeholder.parsed("home", homeName)
                ));
            }
        });
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void sendOnlyPlayersMessage(CommandSender sender) {
        HomeConfig config = configSupplier.get();
        sender.sendMessage(miniMessage.deserialize(
            config.messages().onlyPlayers(),
            Placeholder.parsed("prefix", config.messages().prefix())
        ));
    }

    private void sendDisabledMessage(CommandSender sender) {
        HomeConfig config = configSupplier.get();
        sender.sendMessage(miniMessage.deserialize(
            config.messages().prefix() + "<red>Home module is currently disabled.</red>"
        ));
    }
}
