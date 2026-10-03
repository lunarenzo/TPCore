package com.lunatech.tpcore.module.back.command;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.module.back.service.BackResultStatus;
import com.lunatech.tpcore.module.back.service.BackService;
import com.lunatech.tpcore.util.MessageFormatter;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
                    .requires(src -> hasAnyBackPermission(src.getSender()))
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
                    .then(Commands.argument("index", IntegerArgumentType.integer(1, 50))
                        .requires(src -> src.getSender().hasPermission(Permissions.BACK_USE))
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

    private boolean hasAnyBackPermission(CommandSender sender) {
        return sender.hasPermission(Permissions.BACK_USE)
            || sender.hasPermission(Permissions.BACK_DEATH)
            || sender.hasPermission(Permissions.BACK_LIST)
            || sender.hasPermission(Permissions.BACK_CLEAR);
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

        if (!player.hasPermission(Permissions.BACK_USE)) {
            sendMessage(player, config.messages().noPermission());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        String worldName = service.getLastLocation(player).map(BackLocation::worldName).orElse("unknown");
        service.teleportBack(player).thenAccept(status -> handleResultStatus(player, status, worldName));
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

        if (!player.hasPermission(Permissions.BACK_DEATH)) {
            sendMessage(player, config.messages().noPermission());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        String worldName = service.getLastDeathLocation(player).map(BackLocation::worldName).orElse("unknown");
        service.teleportDeath(player).thenAccept(status -> handleResultStatus(player, status, worldName));
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

        if (!player.hasPermission(Permissions.BACK_USE)) {
            sendMessage(player, config.messages().noPermission());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        int targetIndex = index - 1;
        List<BackLocation> history = service.getHistory(player);
        if (targetIndex < 0 || targetIndex >= history.size()) {
            sendMessage(player, config.messages().noBackLocation());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        String worldName = history.get(targetIndex).worldName();
        service.teleportToHistory(player, targetIndex).thenAccept(status -> handleResultStatus(player, status, worldName));
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

        if (!player.hasPermission(Permissions.BACK_CLEAR)) {
            sendMessage(player, config.messages().noPermission());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        service.clearHistory(player);
        sendMessage(player, config.messages().historyCleared());
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

        if (!player.hasPermission(Permissions.BACK_LIST)) {
            sendMessage(player, config.messages().noPermission());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        BackService service = backServiceSupplier.get();
        if (service == null) {
            sendDisabledMessage(sender);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        List<BackLocation> history = service.getHistory(player);
        if (history.isEmpty()) {
            sendMessage(sender, config.messages().backListEmpty());
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        }

        int perPage = 8;
        int maxPages = (int) Math.ceil((double) history.size() / perPage);
        int targetPage = Math.min(Math.max(1, page), maxPages);

        int startIndex = (targetPage - 1) * perPage;
        int endIndex = Math.min(startIndex + perPage, history.size());

        sendMessage(
            sender,
            config.messages().backListHeader(),
            Placeholder.unparsed("page", String.valueOf(targetPage)),
            Placeholder.unparsed("maxpages", String.valueOf(maxPages))
        );

        long now = System.currentTimeMillis();
        for (int i = startIndex; i < endIndex; i++) {
            BackLocation loc = history.get(i);
            String timeAgo = formatTimeAgo(now - loc.timestamp());
            String causeName = loc.cause() != null ? loc.cause().name() : "TELEPORT";

            sendMessage(
                sender,
                config.messages().backListItem(),
                Placeholder.unparsed("index", String.valueOf(i + 1)),
                Placeholder.unparsed("cause", causeName),
                Placeholder.unparsed("world", loc.worldName()),
                Placeholder.unparsed("x", String.format("%.1f", loc.x())),
                Placeholder.unparsed("y", String.format("%.1f", loc.y())),
                Placeholder.unparsed("z", String.format("%.1f", loc.z())),
                Placeholder.unparsed("time", timeAgo)
            );
        }

        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private void handleResultStatus(Player player, BackResultStatus status, String worldName) {
        if (status == BackResultStatus.ERROR || status == BackResultStatus.WARMUP_IN_PROGRESS) {
            return;
        }

        BackConfig config = configSupplier.get();
        BackService service = backServiceSupplier.get();
        long remainingSecs = service != null ? service.getRemainingCooldownSeconds(player.getUniqueId()) : 0;
        String worldDisplay = (worldName != null && !worldName.isBlank()) ? worldName : "unknown";

        String rawMsg = switch (status) {
            case SUCCESS -> config.messages().teleportSuccess();
            case SUCCESS_ADJUSTED_HAZARD -> config.messages().teleportAdjustedHazard();
            case NO_BACK_LOCATION -> config.messages().noBackLocation();
            case NO_DEATH_LOCATION -> config.messages().noDeathLocation();
            case NO_PERMISSION -> config.messages().noPermission();
            case WORLD_NOT_LOADED -> config.messages().worldNotLoaded();
            case UNSAFE_LOCATION -> config.messages().unsafeLocation();
            case COOLDOWN_ACTIVE -> config.messages().cooldownActive();
            default -> null;
        };

        if (rawMsg == null || rawMsg.isBlank()) {
            return;
        }

        sendMessage(
            player,
            rawMsg,
            Placeholder.unparsed("seconds", String.valueOf(remainingSecs)),
            Placeholder.unparsed("hazard", "Lava/Suffocation"),
            Placeholder.unparsed("world", worldDisplay)
        );
    }

    private void sendMessage(CommandSender sender, String template, TagResolver... customResolvers) {
        if (sender == null || template == null || template.isBlank()) {
            return;
        }
        BackConfig config = configSupplier.get();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        TagResolver combined;
        if (customResolvers == null || customResolvers.length == 0) {
            combined = prefixResolver;
        } else {
            TagResolver[] all = new TagResolver[customResolvers.length + 1];
            all[0] = prefixResolver;
            System.arraycopy(customResolvers, 0, all, 1, customResolvers.length);
            combined = TagResolver.resolver(all);
        }
        sender.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
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
        sendMessage(sender, config.messages().onlyPlayers());
    }

    private void sendDisabledMessage(CommandSender sender) {
        BackConfig config = configSupplier.get();
        sendMessage(sender, config.messages().moduleDisabled());
    }
}
