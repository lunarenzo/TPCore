package com.lunatech.tpcore.module.spawn.command;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import com.lunatech.tpcore.util.MessageFormatter;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class SpawnCommandRegistry {

    private final JavaPlugin plugin;
    private final SpawnService spawnService;
    private final Supplier<SpawnConfig> configSupplier;
    private final MiniMessage miniMessage;

    public SpawnCommandRegistry(JavaPlugin plugin, SpawnService spawnService, Supplier<SpawnConfig> configSupplier) {
        this.plugin = plugin;
        this.spawnService = spawnService;
        this.configSupplier = configSupplier;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            SuggestionProvider<CommandSourceStack> worldSuggestions = (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(world.getName());
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> playerSuggestions = (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(p.getName());
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> spawnArgSuggestions = (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                CommandSender sender = ctx.getSource().getSender();
                if (hasOtherPermission(sender)) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                            builder.suggest(p.getName());
                        }
                    }
                }
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(world.getName());
                    }
                }
                return builder.buildFuture();
            };

            SuggestionProvider<CommandSourceStack> setSpawnTypeSuggestions = (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                if ("global".startsWith(remaining)) builder.suggest("global");
                if ("world".startsWith(remaining)) builder.suggest("world");
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(world.getName());
                    }
                }
                return builder.buildFuture();
            };

            // /spawn [world|player] [world]
            commands.register(
                Commands.literal("spawn")
                    .requires(src -> src.getSender().hasPermission(Permissions.SPAWN_USE) || hasOtherPermission(src.getSender()))
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        if (sender instanceof Player player) {
                            this.spawnService.teleportToSpawn(player, null);
                        } else {
                            this.sendOnlyPlayersMessage(sender);
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("targetOrWorld", StringArgumentType.string())
                        .suggests(spawnArgSuggestions)
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String arg = StringArgumentType.getString(ctx, "targetOrWorld");
                            Player target = Bukkit.getPlayerExact(arg);
                            if (target == null) target = Bukkit.getPlayer(arg);

                            if (target != null && hasOtherPermission(sender)) {
                                this.spawnService.teleportOtherToSpawn(sender, target, null);
                                return Command.SINGLE_SUCCESS;
                            }

                            if (sender instanceof Player player) {
                                this.spawnService.teleportToSpawn(player, arg);
                            } else {
                                this.sendPlayerNotOnline(sender, arg);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("world", StringArgumentType.string())
                            .suggests(worldSuggestions)
                            .requires(src -> hasOtherPermission(src.getSender()))
                            .executes(ctx -> {
                                CommandSender sender = ctx.getSource().getSender();
                                String targetName = StringArgumentType.getString(ctx, "targetOrWorld");
                                String worldName = StringArgumentType.getString(ctx, "world");
                                Player target = Bukkit.getPlayerExact(targetName);
                                if (target == null) target = Bukkit.getPlayer(targetName);

                                if (target != null) {
                                    this.spawnService.teleportOtherToSpawn(sender, target, worldName);
                                } else {
                                    this.sendPlayerNotOnline(sender, targetName);
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                    .build(),
                "Teleport to spawn location or force teleport another player to spawn",
                List.of()
            );

            // /spawnother <target> [world]
            commands.register(
                Commands.literal("spawnother")
                    .requires(src -> hasOtherPermission(src.getSender()))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(playerSuggestions)
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String targetName = StringArgumentType.getString(ctx, "target");
                            Player target = Bukkit.getPlayerExact(targetName);
                            if (target == null) target = Bukkit.getPlayer(targetName);

                            if (target != null) {
                                this.spawnService.teleportOtherToSpawn(sender, target, null);
                            } else {
                                this.sendPlayerNotOnline(sender, targetName);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("world", StringArgumentType.string())
                            .suggests(worldSuggestions)
                            .executes(ctx -> {
                                CommandSender sender = ctx.getSource().getSender();
                                String targetName = StringArgumentType.getString(ctx, "target");
                                String worldName = StringArgumentType.getString(ctx, "world");
                                Player target = Bukkit.getPlayerExact(targetName);
                                if (target == null) target = Bukkit.getPlayer(targetName);

                                if (target != null) {
                                    this.spawnService.teleportOtherToSpawn(sender, target, worldName);
                                } else {
                                    this.sendPlayerNotOnline(sender, targetName);
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                    .build(),
                "Force teleport another player to spawn",
                List.of()
            );

            // /setspawn [typeOrWorld]
            commands.register(
                Commands.literal("setspawn")
                    .requires(src -> src.getSender().hasPermission(Permissions.SPAWN_SET))
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        if (sender instanceof Player player) {
                            this.spawnService.setGlobalSpawn(player);
                        } else {
                            this.sendOnlyPlayersMessage(sender);
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("type", StringArgumentType.string())
                        .suggests(setSpawnTypeSuggestions)
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                String arg = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
                                if (arg.equals("global")) {
                                    this.spawnService.setGlobalSpawn(player);
                                } else if (arg.equals("world")) {
                                    this.spawnService.setWorldSpawn(player, player.getWorld().getName());
                                } else {
                                    String currentWorld = player.getWorld().getName();
                                    if (currentWorld.equalsIgnoreCase(arg)) {
                                        this.spawnService.setWorldSpawn(player, currentWorld);
                                    } else {
                                        SpawnConfig cfg = this.configSupplier.get();
                                        TagResolver prefix = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                                        TagResolver world = Placeholder.unparsed("world", arg);
                                        player.sendMessage(this.miniMessage.deserialize(
                                            MessageFormatter.toMiniMessage(cfg.messages().mustBeInTargetWorld()),
                                            TagResolver.resolver(prefix, world)
                                        ));
                                    }
                                }
                            } else {
                                this.sendOnlyPlayersMessage(sender);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Set global or per-world spawn location",
                List.of()
            );

            // /delspawn [typeOrWorld]
            commands.register(
                Commands.literal("delspawn")
                    .requires(src -> src.getSender().hasPermission(Permissions.SPAWN_DEL))
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        if (sender instanceof Player player) {
                            this.spawnService.deleteGlobalSpawn(player);
                        } else {
                            this.sendOnlyPlayersMessage(sender);
                        }
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("type", StringArgumentType.string())
                        .suggests(setSpawnTypeSuggestions)
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                String arg = StringArgumentType.getString(ctx, "type").toLowerCase(Locale.ROOT);
                                if (arg.equals("global")) {
                                    this.spawnService.deleteGlobalSpawn(player);
                                } else if (arg.equals("world")) {
                                    this.spawnService.deleteWorldSpawn(player, player.getWorld().getName());
                                } else {
                                    this.spawnService.deleteWorldSpawn(player, arg);
                                }
                            } else {
                                this.sendOnlyPlayersMessage(sender);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Delete global or per-world spawn location",
                List.of("removespawn")
            );
        });
    }

    private static boolean hasOtherPermission(CommandSender sender) {
        if (!(sender instanceof Player)) return true;
        return sender.hasPermission(Permissions.SPAWN_OTHER) || sender.hasPermission(Permissions.SPAWN_ADMIN);
    }

    private void sendPlayerNotOnline(CommandSender sender, String playerName) {
        SpawnConfig cfg = this.configSupplier.get();
        TagResolver prefix = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver player = Placeholder.unparsed("player", playerName);
        sender.sendMessage(this.miniMessage.deserialize(
            MessageFormatter.toMiniMessage(cfg.messages().playerNotOnline()),
            TagResolver.resolver(prefix, player)
        ));
    }

    private void sendOnlyPlayersMessage(CommandSender sender) {
        SpawnConfig cfg = this.configSupplier.get();
        TagResolver prefix = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        sender.sendMessage(this.miniMessage.deserialize(
            MessageFormatter.toMiniMessage(cfg.messages().onlyPlayers()),
            prefix
        ));
    }
}
