package com.lunatech.tpcore.module.back.command;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.service.BackResultStatus;
import com.lunatech.tpcore.module.back.service.BackService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BackCommandRegistry {

    private final JavaPlugin plugin;
    private final Supplier<BackService> backServiceSupplier;
    private final Supplier<BackConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public BackCommandRegistry(JavaPlugin plugin, Supplier<BackService> backServiceSupplier, Supplier<BackConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.backServiceSupplier = Objects.requireNonNull(backServiceSupplier, "backServiceSupplier cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                Commands.literal("back")
                    .requires(src -> src.getSender().hasPermission(Permissions.BACK_USE))
                    .executes(ctx -> executeBack(ctx.getSource().getSender()))
                    .then(Commands.literal("death")
                        .requires(src -> src.getSender().hasPermission(Permissions.BACK_DEATH))
                        .executes(ctx -> executeBackDeath(ctx.getSource().getSender()))
                    )
                    .then(Commands.literal("clear")
                        .requires(src -> src.getSender().hasPermission(Permissions.BACK_CLEAR))
                        .executes(ctx -> executeBackClear(ctx.getSource().getSender()))
                    )
                    .then(Commands.literal("list")
                        .requires(src -> src.getSender().hasPermission(Permissions.BACK_LIST))
                        .executes(ctx -> executeBackList(ctx.getSource().getSender(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> executeBackList(
                                ctx.getSource().getSender(),
                                IntegerArgumentType.getInteger(ctx, "page")
                            ))
                        )
                    )
                    .then(Commands.argument("index", IntegerArgumentType.integer(0, 50))
                        .executes(ctx -> executeBackIndex(
                            ctx.getSource().getSender(),
                            IntegerArgumentType.getInteger(ctx, "index")
                        ))
                    )
                    .build(),
                "Teleport back to your previous location or death spot",
                List.of("return")
            );
        });
    }

    private int executeBack(CommandSender sender) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        service.teleportBack(player).thenAccept(status -> handleResultStatus(player, status));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeBackDeath(CommandSender sender) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        service.teleportDeath(player).thenAccept(status -> handleResultStatus(player, status));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeBackIndex(CommandSender sender, int index) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        service.teleportToHistory(player, index).thenAccept(status -> handleResultStatus(player, status));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeBackClear(CommandSender sender) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        service.clearHistory(player);
        player.sendMessage(miniMessage.deserialize(config.messages().prefix() + config.messages().historyCleared()));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private int executeBackList(CommandSender sender, int page) {
        BackConfig config = configSupplier.get();
        if (!config.enabled()) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        if (!(sender instanceof Player player)) {
            sendOnlyPlayersMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        List<BackLocation> history = service.getHistory(player);
        if (history.isEmpty()) {
            sender.sendMessage(miniMessage.deserialize(config.messages().prefix() + config.messages().backListEmpty()));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        int perPage = 8;
        int maxPages = (int) Math.ceil((double) history.size() / perPage);
        int targetPage = Math.min(Math.max(1, page), maxPages);

        int startIndex = (targetPage - 1) * perPage;
        int endIndex = Math.min(startIndex + perPage, history.size());

        sender.sendMessage(miniMessage.deserialize(
            config.messages().prefix() + config.messages().backListHeader(),
            Placeholder.unparsed("page", String.valueOf(targetPage)),
            Placeholder.unparsed("maxpages", String.valueOf(maxPages))
        ));

        long now = System.currentTimeMillis();
        for (int i = startIndex; i < endIndex; i++) {
            BackLocation loc = history.get(i);
            String timeAgo = formatTimeAgo(now - loc.timestamp());
            String causeName = loc.cause() != null ? loc.cause().name() : "TELEPORT";

            String rawItemPattern = config.messages().backListItem().replace("<index>", String.valueOf(i));
            Component item = miniMessage.deserialize(
                rawItemPattern,
                Placeholder.unparsed("index", String.valueOf(i)),
                Placeholder.unparsed("cause", causeName),
                Placeholder.unparsed("world", loc.worldName()),
                Placeholder.unparsed("x", String.format("%.1f", loc.x())),
                Placeholder.unparsed("y", String.format("%.1f", loc.y())),
                Placeholder.unparsed("z", String.format("%.1f", loc.z())),
                Placeholder.unparsed("time", timeAgo)
            );
            sender.sendMessage(item);
        }


        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void handleResultStatus(Player player, BackResultStatus status) {
        BackConfig config = configSupplier.get();
        BackService service = backServiceSupplier.get();
        long remainingSecs = service != null ? service.getRemainingCooldownSeconds(player.getUniqueId()) : 0;

        String rawMsg = switch (status) {
            case SUCCESS -> config.messages().prefix() + config.messages().teleportSuccess();
            case SUCCESS_ADJUSTED_HAZARD -> config.messages().prefix() + config.messages().teleportAdjustedHazard();
            case NO_BACK_LOCATION -> config.messages().prefix() + config.messages().noBackLocation();
            case NO_DEATH_LOCATION -> config.messages().prefix() + config.messages().noDeathLocation();
            case NO_PERMISSION -> config.messages().prefix() + config.messages().noPermission();
            case WORLD_NOT_LOADED -> config.messages().prefix() + config.messages().worldNotLoaded();
            case UNSAFE_LOCATION -> config.messages().prefix() + config.messages().unsafeLocation();
            case COOLDOWN_ACTIVE -> config.messages().prefix() + config.messages().cooldownActive();
            default -> config.messages().prefix() + config.messages().noBackLocation();
        };

        player.sendMessage(miniMessage.deserialize(
            rawMsg,
            Placeholder.unparsed("seconds", String.valueOf(remainingSecs)),
            Placeholder.unparsed("hazard", "Lava/Suffocation")
        ));
    }

    private String formatTimeAgo(long elapsedMs) {
        long sec = Math.max(0, elapsedMs / 1000);
        if (sec < 60) {
            return sec + "s ago";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + "m ago";
        }
        long hr = min / 60;
        return hr + "h ago";
    }

    private void sendOnlyPlayersMessage(CommandSender sender) {
        BackConfig config = configSupplier.get();
        sender.sendMessage(miniMessage.deserialize(
            config.messages().onlyPlayers(),
            Placeholder.unparsed("prefix", config.messages().prefix())
        ));
    }

    private void sendDisabledMessage(CommandSender sender) {
        BackConfig config = configSupplier.get();
        sender.sendMessage(miniMessage.deserialize(
            config.messages().prefix() + config.messages().moduleDisabled()
        ));
    }
}
