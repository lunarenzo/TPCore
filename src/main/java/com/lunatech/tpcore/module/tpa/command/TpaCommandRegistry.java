package com.lunatech.tpcore.module.tpa.command;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class TpaCommandRegistry {

    private final JavaPlugin plugin;
    private final TpaService tpaService;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaConfirmationMenuService confirmationMenuService;
    private final MiniMessage miniMessage;

    public TpaCommandRegistry(
        JavaPlugin plugin,
        TpaService tpaService,
        Supplier<TpaConfig> configSupplier,
        TpaConfirmationMenuService confirmationMenuService
    ) {
        this.plugin = plugin;
        this.tpaService = tpaService;
        this.configSupplier = configSupplier;
        this.confirmationMenuService = confirmationMenuService;
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
                                List<Player> rawTargets = resolver.resolve(src);
                                TpaConfig cfg = this.configSupplier.get();
                                List<Player> targets = new java.util.ArrayList<>();
                                for (Player p : rawTargets) {
                                    if (p != null && p.isOnline() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR) {
                                        if (cfg.allowSelfTpa() || !p.getUniqueId().equals(sender.getUniqueId())) {
                                            targets.add(p);
                                        }
                                    }
                                }
                                if (targets.size() > 1 && !hasBulkPermission(sender)) {
                                    sender.sendMessage(this.miniMessage.deserialize(
                                        cfg.messages().noBulkPermission(),
                                        Placeholder.parsed("prefix", cfg.messages().prefix())
                                    ));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                }
                                if (!targets.isEmpty()) {
                                    if (targets.size() > 1) {
                                        this.tpaService.sendBulkRequests(sender, targets, TpaType.TPA_TO);
                                    } else {
                                        Player target = targets.get(0);
                                        handleSendOrMenu(sender, target, TpaType.TPA_TO);
                                    }
                                } else {
                                    boolean hadSelf = false;
                                    for (Player p : rawTargets) {
                                        if (p != null && p.getUniqueId().equals(sender.getUniqueId())) {
                                            hadSelf = true;
                                            break;
                                        }
                                    }
                                    if (hadSelf && !cfg.allowSelfTpa()) {
                                        sender.sendMessage(this.miniMessage.deserialize(
                                            cfg.messages().rejectSelfTpa(),
                                            Placeholder.parsed("prefix", cfg.messages().prefix())
                                        ));
                                    } else {
                                        String targetArg = extractTargetArg(ctx.getInput());
                                        sender.sendMessage(this.miniMessage.deserialize(
                                            cfg.messages().playerNotOnline(),
                                            Placeholder.parsed("prefix", cfg.messages().prefix()),
                                            Placeholder.unparsed("player", targetArg)
                                        ));
                                    }
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
                                List<Player> rawTargets = resolver.resolve(src);
                                TpaConfig cfg = this.configSupplier.get();
                                List<Player> targets = new java.util.ArrayList<>();
                                for (Player p : rawTargets) {
                                    if (p != null && p.isOnline() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR) {
                                        if (cfg.allowSelfTpa() || !p.getUniqueId().equals(sender.getUniqueId())) {
                                            targets.add(p);
                                        }
                                    }
                                }
                                if (targets.size() > 1 && !hasBulkPermission(sender)) {
                                    sender.sendMessage(this.miniMessage.deserialize(
                                        cfg.messages().noBulkPermission(),
                                        Placeholder.parsed("prefix", cfg.messages().prefix())
                                    ));
                                    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                }
                                if (!targets.isEmpty()) {
                                    if (targets.size() > 1) {
                                        this.tpaService.sendBulkRequests(sender, targets, TpaType.TPA_HERE);
                                    } else {
                                        Player target = targets.get(0);
                                        handleSendOrMenu(sender, target, TpaType.TPA_HERE);
                                    }
                                } else {
                                    boolean hadSelf = false;
                                    for (Player p : rawTargets) {
                                        if (p != null && p.getUniqueId().equals(sender.getUniqueId())) {
                                            hadSelf = true;
                                            break;
                                        }
                                    }
                                    if (hadSelf && !cfg.allowSelfTpa()) {
                                        sender.sendMessage(this.miniMessage.deserialize(
                                            cfg.messages().rejectSelfTpa(),
                                            Placeholder.parsed("prefix", cfg.messages().prefix())
                                        ));
                                    } else {
                                        String targetArg = extractTargetArg(ctx.getInput());
                                        sender.sendMessage(this.miniMessage.deserialize(
                                            cfg.messages().playerNotOnline(),
                                            Placeholder.parsed("prefix", cfg.messages().prefix()),
                                            Placeholder.unparsed("player", targetArg)
                                        ));
                                    }
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
                            handleAcceptOrMenu(target, null);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("player", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getSender() instanceof Player target) {
                                Collection<TpaRequest> requests = this.tpaService.getPendingRequestsForTarget(target);
                                if (requests != null && !requests.isEmpty()) {
                                    String remaining = builder.getRemainingLowerCase();
                                     for (TpaRequest req : requests) {
                                        Player p = Bukkit.getPlayer(req.senderId());
                                        String name = (p != null && p.isOnline()) ? p.getName() : null;
                                        if (name == null) {
                                            OfflinePlayer op = resolveOfflinePlayerIfCached(req.senderId());
                                            name = (op != null) ? op.getName() : null;
                                        }
                                        if (name != null && (remaining.isBlank() || name.regionMatches(true, 0, remaining, 0, remaining.length()))) {
                                            builder.suggest(name);
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player target) {
                                String senderName = StringArgumentType.getString(ctx, "player");
                                handleAcceptOrMenu(target, senderName);
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
                    .then(Commands.argument("player", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getSender() instanceof Player target) {
                                Collection<TpaRequest> requests = this.tpaService.getPendingRequestsForTarget(target);
                                if (requests != null && !requests.isEmpty()) {
                                    String remaining = builder.getRemainingLowerCase();
                                    for (TpaRequest req : requests) {
                                        Player p = Bukkit.getPlayer(req.senderId());
                                        String name = (p != null && p.isOnline()) ? p.getName() : null;
                                        if (name == null) {
                                            OfflinePlayer op = resolveOfflinePlayerIfCached(req.senderId());
                                            name = (op != null) ? op.getName() : null;
                                        }
                                        if (name != null && (remaining.isBlank() || name.regionMatches(true, 0, remaining, 0, remaining.length()))) {
                                            builder.suggest(name);
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player target) {
                                String senderName = StringArgumentType.getString(ctx, "player");
                                this.tpaService.denyRequest(target, senderName);
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
                    .then(Commands.argument("player", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getSender() instanceof Player sender) {
                                Collection<TpaRequest> requests = this.tpaService.getOutgoingRequestsForSender(sender);
                                if (requests != null && !requests.isEmpty()) {
                                    String remaining = builder.getRemainingLowerCase();
                                    for (TpaRequest req : requests) {
                                        Player p = Bukkit.getPlayer(req.targetId());
                                        String name = (p != null && p.isOnline()) ? p.getName() : null;
                                        if (name == null) {
                                            OfflinePlayer op = resolveOfflinePlayerIfCached(req.targetId());
                                            name = (op != null) ? op.getName() : null;
                                        }
                                        if (name != null && (remaining.isBlank() || name.regionMatches(true, 0, remaining, 0, remaining.length()))) {
                                            builder.suggest(name);
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player sender) {
                                String targetName = StringArgumentType.getString(ctx, "player");
                                this.tpaService.cancelRequest(sender, targetName);
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

            // /tpaautoaccept
            commands.register(
                Commands.literal("tpaautoaccept")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_AUTOACCEPT))
                    .executes(ctx -> {
                        if (ctx.getSource().getSender() instanceof Player player) {
                            this.tpaService.toggleAutoAccept(player);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .build(),
                "Toggle automatically accepting incoming TPA requests",
                List.of("tpautoaccept", "tpaa")
            );

            // /tpablock <player>
            commands.register(
                Commands.literal("tpablock")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_BLOCK))
                    .then(Commands.argument("player", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getSender() instanceof Player sender) {
                                String remaining = builder.getRemainingLowerCase();
                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    if (p != null && p.isOnline() && !p.getUniqueId().equals(sender.getUniqueId())) {
                                        if (remaining.isBlank() || p.getName().regionMatches(true, 0, remaining, 0, remaining.length())) {
                                            builder.suggest(p.getName());
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player sender) {
                                String targetName = StringArgumentType.getString(ctx, "player");
                                this.tpaService.blockPlayer(sender, targetName);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Block a player from sending TPA requests",
                List.of("tpblock")
            );

            // /tpaunblock <player>
            commands.register(
                Commands.literal("tpaunblock")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_UNBLOCK))
                    .then(Commands.argument("player", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            if (ctx.getSource().getSender() instanceof Player sender) {
                                Set<UUID> blocked = this.tpaService.getBlockedPlayers(sender);
                                if (blocked != null && !blocked.isEmpty()) {
                                    String remaining = builder.getRemainingLowerCase();
                                    for (UUID uuid : blocked) {
                                        Player p = Bukkit.getPlayer(uuid);
                                        String name = (p != null && p.isOnline()) ? p.getName() : null;
                                        if (name == null) {
                                            OfflinePlayer op = resolveOfflinePlayerIfCached(uuid);
                                            name = (op != null) ? op.getName() : null;
                                        }
                                        String uuidStr = uuid.toString();
                                        String suggested = (name != null) ? name : uuidStr;
                                        if (remaining.isBlank() || suggested.regionMatches(true, 0, remaining, 0, remaining.length()) || uuidStr.regionMatches(true, 0, remaining, 0, remaining.length())) {
                                            builder.suggest(suggested);
                                        }
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            if (src.getSender() instanceof Player sender) {
                                String targetName = StringArgumentType.getString(ctx, "player");
                                this.tpaService.unblockPlayer(sender, targetName);
                            }
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .build(),
                "Unblock a player from sending TPA requests",
                List.of("tpunblock")
            );

            // /tpablocklist
            commands.register(
                Commands.literal("tpablocklist")
                    .requires(src -> src.getSender().hasPermission(Permissions.TPA_BLOCKLIST))
                    .executes(ctx -> {
                        CommandSourceStack src = ctx.getSource();
                        if (src.getSender() instanceof Player sender) {
                            this.tpaService.listBlockedPlayers(sender);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .build(),
                "List all blocked players",
                List.of("tpblocklist")
            );
        });
    }

    private boolean hasBulkPermission(Player sender) {
        return sender != null && (sender.hasPermission(Permissions.TPA_ALL) || sender.hasPermission(Permissions.TPA_ADMIN));
    }

    private boolean isConfirmationEnabled() {
        TpaConfig cfg = this.configSupplier.get();
        if (!cfg.enableConfirmationMenu()) {
            return false;
        }
        String mode = (cfg.confirmationMode() != null) ? cfg.confirmationMode().trim().toUpperCase(Locale.ROOT) : "GUI";
        return !"CHAT".equals(mode);
    }

    private void handleSendOrMenu(Player sender, Player target, TpaType type) {
        TpaConfig cfg = this.configSupplier.get();
        if (sender != null && target != null && !cfg.allowSelfTpa() && sender.getUniqueId().equals(target.getUniqueId())) {
            this.tpaService.sendRequest(sender, target, type);
            return;
        }

        boolean specificToggle = (type == TpaType.TPA_HERE) ? cfg.enableTpahereConfirm() : cfg.enableTpaConfirm();

        if (isConfirmationEnabled() && specificToggle && this.confirmationMenuService != null) {
            this.confirmationMenuService.openSendConfirmation(sender, target, type);
            return;
        }

        this.tpaService.sendRequest(sender, target, type);
    }

    private void handleAcceptOrMenu(Player target, String optionalSenderName) {
        TpaConfig cfg = this.configSupplier.get();

        if (isConfirmationEnabled() && cfg.enableTpacceptConfirm() && this.confirmationMenuService != null) {
            TpaRequest req = this.tpaService.findPendingRequest(target, optionalSenderName);
            if (req != null) {
                this.confirmationMenuService.openConfirmation(target, req);
                return;
            }
        }

        this.tpaService.acceptRequest(target, optionalSenderName);
    }

    private static String extractTargetArg(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "Player";
        }
        String trimmed = rawInput.trim();
        int spaceIdx = trimmed.lastIndexOf(' ');
        if (spaceIdx >= 0 && spaceIdx < trimmed.length() - 1) {
            String arg = trimmed.substring(spaceIdx + 1).trim();
            if (!arg.isEmpty()) {
                return arg;
            }
        }
        return "Player";
    }

    private static final Method GET_OFFLINE_PLAYER_IF_CACHED_UUID;
    static {
        Method mUuid = null;
        try {
            mUuid = Bukkit.class.getMethod("getOfflinePlayerIfCached", UUID.class);
            mUuid.setAccessible(true);
        } catch (Throwable ignored) {
        }
        GET_OFFLINE_PLAYER_IF_CACHED_UUID = mUuid;
    }

    private static OfflinePlayer resolveOfflinePlayerIfCached(UUID uuid) {
        if (GET_OFFLINE_PLAYER_IF_CACHED_UUID != null && uuid != null) {
            try {
                OfflinePlayer op = (OfflinePlayer) GET_OFFLINE_PLAYER_IF_CACHED_UUID.invoke(null, uuid);
                if (op != null && op.getName() != null) {
                    return op;
                }
            } catch (Throwable ignored) {
            }
        }
        if (uuid != null) {
            try {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                if (op != null && op.getName() != null) {
                    return op;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
