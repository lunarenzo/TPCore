package com.lunatech.tpcore.module.tpa.command;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.Supplier;

public final class TpaCommandRegistry {

    private final JavaPlugin plugin;
    private final TpaService tpaService;
    private final Supplier<TpaConfig> configSupplier;
    private final MiniMessage miniMessage;

    public TpaCommandRegistry(JavaPlugin plugin, TpaService tpaService, Supplier<TpaConfig> configSupplier) {
        this.plugin = plugin;
        this.tpaService = tpaService;
        this.configSupplier = configSupplier;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            // /tpa <player>
            commands.register(
                Commands.literal("tpa")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_USE))
                    .then(Commands.argument("player", ArgumentTypes.players())
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player sender) {
                                PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                List<Player> targets = resolver.resolve(src);
                                if (!targets.isEmpty()) {
                                    for (Player target : targets) {
                                        if (target != null && target.isOnline()) {
                                            this.tpaService.sendRequest(sender, target, TpaType.TPA_TO);
                                        }
                                    }
                                } else {
                                    TpaConfig cfg = this.configSupplier.get();
                                    String msg = cfg.messages().playerNotOnline().replace("<player>", "Player");
                                    sender.sendMessage(this.miniMessage.deserialize(
                                        msg,
                                        Placeholder.parsed("prefix", cfg.messages().prefix()),
                                        Placeholder.unparsed("player", "Player")
                                    ));
                                }
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Request to teleport to a player",
                List.of()
            );

            // /tpahere <player>
            commands.register(
                Commands.literal("tpahere")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_HERE))
                    .then(Commands.argument("player", ArgumentTypes.players())
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player sender) {
                                PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                List<Player> targets = resolver.resolve(src);
                                if (!targets.isEmpty()) {
                                    for (Player target : targets) {
                                        if (target != null && target.isOnline()) {
                                            this.tpaService.sendRequest(sender, target, TpaType.TPA_HERE);
                                        }
                                    }
                                } else {
                                    TpaConfig cfg = this.configSupplier.get();
                                    String msg = cfg.messages().playerNotOnline().replace("<player>", "Player");
                                    sender.sendMessage(this.miniMessage.deserialize(
                                        msg,
                                        Placeholder.parsed("prefix", cfg.messages().prefix()),
                                        Placeholder.unparsed("player", "Player")
                                    ));
                                }
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Request a player to teleport to you",
                List.of()
            );

            // /tpaccept [player]
            commands.register(
                Commands.literal("tpaccept")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_ACCEPT))
                    .executes(ctx -> {
                        if (ctx.getSource().getSender() instanceof Player target) {
                            this.tpaService.acceptRequest(target, null);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player target) {
                                PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                Player sender = resolver.resolve(src).stream().findFirst().orElse(null);
                                this.tpaService.acceptRequest(target, (sender != null) ? sender.getName() : null);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Accept a pending teleport request",
                List.of()
            );

            // /tpdeny [player]
            commands.register(
                Commands.literal("tpdeny")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_DENY))
                    .executes(ctx -> {
                        if (ctx.getSource().getSender() instanceof Player target) {
                            this.tpaService.denyRequest(target, null);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player target) {
                                PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                Player sender = resolver.resolve(src).stream().findFirst().orElse(null);
                                this.tpaService.denyRequest(target, (sender != null) ? sender.getName() : null);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Deny a pending teleport request",
                List.of()
            );

            // /tpcancel [player]
            commands.register(
                Commands.literal("tpcancel")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_CANCEL))
                    .executes(ctx -> {
                        if (ctx.getSource().getSender() instanceof Player sender) {
                            this.tpaService.cancelRequest(sender, null);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player sender) {
                                PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                                Player target = resolver.resolve(src).stream().findFirst().orElse(null);
                                this.tpaService.cancelRequest(sender, (target != null) ? target.getName() : null);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Cancel an outgoing teleport request",
                List.of()
            );

            // /tpatoggle
            commands.register(
                Commands.literal("tpatoggle")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_TOGGLE))
                    .executes(ctx -> {
                        if (ctx.getSource().getSender() instanceof Player player) {
                            this.tpaService.toggleTpa(player);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .build(),
                "Toggle receiving teleport requests",
                List.of("tptoggle")
            );
        });
    }
}
