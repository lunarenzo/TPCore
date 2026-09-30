package com.lunatech.tpcore.module.tpa.command;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationMenuService;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Brigadier command registrar for teleport request dispatch commands (/tpa and /tpahere).
 */
final class TpaTeleportCommands {

    private final TpaService tpaService;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaConfirmationMenuService confirmationMenuService;
    private final MiniMessage miniMessage;

    TpaTeleportCommands(
        TpaService tpaService,
        Supplier<TpaConfig> configSupplier,
        TpaConfirmationMenuService confirmationMenuService
    ) {
        this.tpaService = tpaService;
        this.configSupplier = configSupplier;
        this.confirmationMenuService = confirmationMenuService;
        this.miniMessage = MiniMessage.miniMessage();
    }

    void register(Commands commands) {
        // /tpa <player>
        commands.register(
            Commands.literal("tpa")
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_USE))
                .then(Commands.argument("player", ArgumentTypes.players())
                    .executes(ctx -> {
                        CommandSourceStack src = ctx.getSource();
                        if (src.getSender() instanceof Player sender) {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                            List<Player> rawTargets = resolver.resolve(src);
                            executeTeleportRequest(ctx, sender, rawTargets, TpaType.TPA_TO);
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
                .requires(src -> this.configSupplier.get().enabled() && src.getSender().hasPermission(Permissions.TPA_HERE))
                .then(Commands.argument("player", ArgumentTypes.players())
                    .executes(ctx -> {
                        CommandSourceStack src = ctx.getSource();
                        if (src.getSender() instanceof Player sender) {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
                            List<Player> rawTargets = resolver.resolve(src);
                            executeTeleportRequest(ctx, sender, rawTargets, TpaType.TPA_HERE);
                        }
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                )
                .build(),
            "Request a player to teleport to you",
            List.of()
        );
    }

    private void executeTeleportRequest(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, Player sender, List<Player> rawTargets, TpaType type) {
        TpaConfig cfg = this.configSupplier.get();
        List<Player> targets = new ArrayList<>();
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
            return;
        }
        if (!targets.isEmpty()) {
            if (targets.size() > 1) {
                this.tpaService.sendBulkRequests(sender, targets, type);
            } else {
                Player target = targets.get(0);
                handleSendOrMenu(sender, target, type);
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
}
