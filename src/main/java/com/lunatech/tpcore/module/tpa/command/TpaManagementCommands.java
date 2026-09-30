package com.lunatech.tpcore.module.tpa.command;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Brigadier command registrar for TPA state and session management commands.
 */
final class TpaManagementCommands {

    private final TpaService tpaService;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaConfirmationMenuService confirmationMenuService;

    TpaManagementCommands(
        TpaService tpaService,
        Supplier<TpaConfig> configSupplier,
        TpaConfirmationMenuService confirmationMenuService
    ) {
        this.tpaService = tpaService;
        this.configSupplier = configSupplier;
        this.confirmationMenuService = confirmationMenuService;
    }

    void register(Commands commands) {
        registerAccept(commands);
        registerDeny(commands);
        registerCancel(commands);
        registerToggle(commands);
        registerAutoAccept(commands);
        registerBlock(commands);
        registerUnblock(commands);
        registerBlockList(commands);
    }

    private void registerAccept(Commands commands) {
        commands.register(
            Commands.literal("tpaccept")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_ACCEPT))
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
    }

    private void registerDeny(Commands commands) {
        commands.register(
            Commands.literal("tpdeny")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_DENY))
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
    }

    private void registerCancel(Commands commands) {
        commands.register(
            Commands.literal("tpcancel")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_CANCEL))
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
    }

    private void registerToggle(Commands commands) {
        commands.register(
            Commands.literal("tpatoggle")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_TOGGLE))
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
    }

    private void registerAutoAccept(Commands commands) {
        commands.register(
            Commands.literal("tpaautoaccept")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_AUTOACCEPT))
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
    }

    private void registerBlock(Commands commands) {
        commands.register(
            Commands.literal("tpablock")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_BLOCK))
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
    }

    private void registerUnblock(Commands commands) {
        commands.register(
            Commands.literal("tpaunblock")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_UNBLOCK))
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
    }

    private void registerBlockList(Commands commands) {
        commands.register(
            Commands.literal("tpablocklist")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_BLOCKLIST))
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
    }

    private boolean isConfirmationEnabled() {
        TpaConfig cfg = this.configSupplier.get();
        if (!cfg.enableConfirmationMenu()) {
            return false;
        }
        String mode = (cfg.confirmationMode() != null) ? cfg.confirmationMode().trim().toUpperCase(Locale.ROOT) : "GUI";
        return !"CHAT".equals(mode);
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
        return null;
    }
}
