package com.lunatech.tpcore.module.spawn.command;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
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

            // /spawn [world]
            commands.register(
                Commands.literal("spawn")
                    .requires(src -> src.getSender().hasPermission(Permissions.SPAWN_USE))
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        if (sender instanceof Player player) {
                            this.spawnService.teleportToSpawn(player, null);
                        } else {
                            this.sendOnlyPlayersMessage(sender);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("world", StringArgumentType.string())
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                String worldName = StringArgumentType.getString(ctx, "world");
                                this.spawnService.teleportToSpawn(player, worldName);
                            } else {
                                this.sendOnlyPlayersMessage(sender);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Teleport to spawn location",
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
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("type", StringArgumentType.string())
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                String arg = StringArgumentType.getString(ctx, "type").toLowerCase();
                                if (arg.equals("global")) {
                                    this.spawnService.setGlobalSpawn(player);
                                } else if (arg.equals("world")) {
                                    this.spawnService.setWorldSpawn(player, player.getWorld().getName());
                                } else {
                                    this.spawnService.setWorldSpawn(player, arg);
                                }
                            } else {
                                this.sendOnlyPlayersMessage(sender);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
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
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("type", StringArgumentType.string())
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (sender instanceof Player player) {
                                String arg = StringArgumentType.getString(ctx, "type").toLowerCase();
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
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Delete global or per-world spawn location",
                List.of("removespawn")
            );
        });
    }

    private void sendOnlyPlayersMessage(CommandSender sender) {
        SpawnConfig cfg = this.configSupplier.get();
        sender.sendMessage(this.miniMessage.deserialize(
            cfg.messages().onlyPlayers(),
            Placeholder.parsed("prefix", cfg.messages().prefix())
        ));
    }
}
