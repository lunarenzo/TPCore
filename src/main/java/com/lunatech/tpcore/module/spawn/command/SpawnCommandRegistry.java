package com.lunatech.tpcore.module.spawn.command;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.spawn.service.SpawnService;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class SpawnCommandRegistry {

    private final JavaPlugin plugin;
    private final SpawnService spawnService;

    public SpawnCommandRegistry(JavaPlugin plugin, SpawnService spawnService) {
        this.plugin = plugin;
        this.spawnService = spawnService;
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            // /spawn [world]
            commands.register(
                Commands.literal("spawn")
                    .requires(src -> src.getSender().hasPermission(Permissions.SPAWN_USE))
                    .executes(ctx -> {
                        if (ctx.getSource().getSender() instanceof Player player) {
                            this.spawnService.teleportToSpawn(player, null);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("world", StringArgumentType.word())
                        .executes(ctx -> {
                            if (ctx.getSource().getSender() instanceof Player player) {
                                String worldName = StringArgumentType.getString(ctx, "world");
                                this.spawnService.teleportToSpawn(player, worldName);
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
                        if (ctx.getSource().getSender() instanceof Player player) {
                            this.spawnService.setGlobalSpawn(player);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("type", StringArgumentType.word())
                        .executes(ctx -> {
                            if (ctx.getSource().getSender() instanceof Player player) {
                                String arg = StringArgumentType.getString(ctx, "type").toLowerCase();
                                if (arg.equals("global")) {
                                    this.spawnService.setGlobalSpawn(player);
                                } else if (arg.equals("world")) {
                                    this.spawnService.setWorldSpawn(player, player.getWorld().getName());
                                } else {
                                    this.spawnService.setWorldSpawn(player, arg);
                                }
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
                        if (ctx.getSource().getSender() instanceof Player player) {
                            this.spawnService.deleteGlobalSpawn(player);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("type", StringArgumentType.word())
                        .executes(ctx -> {
                            if (ctx.getSource().getSender() instanceof Player player) {
                                String arg = StringArgumentType.getString(ctx, "type").toLowerCase();
                                if (arg.equals("global")) {
                                    this.spawnService.deleteGlobalSpawn(player);
                                } else if (arg.equals("world")) {
                                    this.spawnService.deleteWorldSpawn(player, player.getWorld().getName());
                                } else {
                                    this.spawnService.deleteWorldSpawn(player, arg);
                                }
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
}
