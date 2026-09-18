package com.lunatech.tpcore.module.rtp.command;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.service.RtpService;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Brigadier command registry for RTP commands (/rtp, /rtp world <world>, /rtp reload).
 */
public final class RtpCommandRegistry {

    private final JavaPlugin plugin;
    private final Supplier<RtpService> rtpServiceSupplier;
    private final Supplier<RtpConfig> configSupplier;
    private final Runnable reloadAction;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public RtpCommandRegistry(
        JavaPlugin plugin,
        Supplier<RtpService> rtpServiceSupplier,
        Supplier<RtpConfig> configSupplier,
        Runnable reloadAction
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.rtpServiceSupplier = Objects.requireNonNull(rtpServiceSupplier, "rtpServiceSupplier cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction cannot be null");
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                Commands.literal("rtp")
                    .requires(src -> src.getSender().hasPermission(Permissions.RTP_USE))
                    .executes(ctx -> executeRtpSelf(ctx.getSource().getSender()))
                    .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission(Permissions.RTP_ADMIN) || src.getSender().hasPermission(Permissions.ADMIN_RELOAD))
                        .executes(ctx -> executeReload(ctx.getSource().getSender()))
                    )
                    .then(Commands.literal("world")
                        .requires(src -> src.getSender().hasPermission(Permissions.RTP_WORLD))
                        .then(Commands.argument("targetWorld", StringArgumentType.string())
                            .suggests((ctx, builder) -> {
                                for (World world : Bukkit.getWorlds()) {
                                    if (world.getName().toLowerCase().startsWith(builder.getRemaining().toLowerCase())) {
                                        builder.suggest(world.getName());
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> executeRtpWorld(
                                ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "targetWorld")
                            ))
                        )
                    )
                    .build(),
                "Randomly teleport across active safe regions",
                List.of("wild", "randomtp")
            );
        });
    }

    private int executeRtpSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.miniMessage.deserialize(this.configSupplier.get().messages().prefix() + this.configSupplier.get().messages().onlyPlayers()));
            return Command.SINGLE_SUCCESS;
        }

        RtpService service = this.rtpServiceSupplier.get();
        if (service == null) {
            sendMessage(player, this.configSupplier.get().messages().disabled());
            return Command.SINGLE_SUCCESS;
        }

        service.executeRtp(player);
        return Command.SINGLE_SUCCESS;
    }

    private int executeRtpWorld(CommandSender sender, String worldName) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.miniMessage.deserialize(this.configSupplier.get().messages().prefix() + this.configSupplier.get().messages().onlyPlayers()));
            return Command.SINGLE_SUCCESS;
        }

        World targetWorld = Bukkit.getWorld(worldName);
        if (targetWorld == null) {
            sendMessage(
                player,
                this.configSupplier.get().messages().worldNotFound(),
                Placeholder.unparsed("world", worldName)
            );
            return Command.SINGLE_SUCCESS;
        }

        RtpService service = this.rtpServiceSupplier.get();
        if (service == null) {
            sendMessage(player, this.configSupplier.get().messages().disabled());
            return Command.SINGLE_SUCCESS;
        }

        service.executeRtp(player, targetWorld);
        return Command.SINGLE_SUCCESS;
    }

    private int executeReload(CommandSender sender) {
        this.reloadAction.run();
        sender.sendMessage(this.miniMessage.deserialize(this.configSupplier.get().messages().prefix() + this.configSupplier.get().messages().reloadSuccess()));
        return Command.SINGLE_SUCCESS;
    }

    private void sendMessage(Player player, String messageFormat, TagResolver... resolvers) {
        if (messageFormat == null || messageFormat.isBlank()) {
            return;
        }
        String prefix = this.configSupplier.get().messages().prefix();
        Component messageComponent = this.miniMessage.deserialize(prefix + messageFormat, resolvers);
        player.sendMessage(messageComponent);
    }
}
