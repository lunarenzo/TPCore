package com.lunatech.tpcore.module.back.service.impl;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.util.MessageFormatter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class BackWarmupManager {

    private final Plugin plugin;
    private final Supplier<BackConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, WarmupSession> activeWarmups = new ConcurrentHashMap<>();

    public record WarmupSession(ScheduledTask task, BackLocation targetLocation) {}

    public BackWarmupManager(Plugin plugin, Supplier<BackConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    public boolean hasActiveWarmup(UUID uuid) {
        return uuid != null && activeWarmups.containsKey(uuid);
    }

    public void startWarmup(Player player, BackLocation targetBackLoc, int warmupSeconds, Runnable onComplete, Runnable onCancel) {
        UUID uuid = player.getUniqueId();
        cancelWarmup(uuid);

        BackConfig config = configSupplier.get();
        String rawPrefix = config.messages().prefix();
        String rawWarmup = config.messages().warmupStart();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(rawPrefix));
        TagResolver secondsResolver = Placeholder.unparsed("seconds", String.valueOf(warmupSeconds));

        player.sendMessage(miniMessage.deserialize(
            MessageFormatter.toMiniMessage(rawPrefix + rawWarmup),
            TagResolver.resolver(prefixResolver, secondsResolver)
        ));

        long delayTicks = warmupSeconds * 20L;
        ScheduledTask scheduledTask = player.getScheduler().runDelayed(plugin, task -> {
            activeWarmups.remove(uuid);
            if (onComplete != null) {
                onComplete.run();
            }
        }, () -> {
            activeWarmups.remove(uuid);
            if (onCancel != null) {
                onCancel.run();
            }
        }, delayTicks);

        if (scheduledTask != null) {
            activeWarmups.put(uuid, new WarmupSession(scheduledTask, targetBackLoc));
        } else if (onComplete != null) {
            onComplete.run();
        }
    }

    public void cancelWarmup(UUID uuid) {
        WarmupSession session = activeWarmups.remove(uuid);
        if (session != null && session.task() != null) {
            session.task().cancel();
        }
    }

    public void handlePlayerMove(Player player) {
        UUID uuid = player.getUniqueId();
        WarmupSession session = activeWarmups.remove(uuid);
        if (session != null) {
            if (session.task() != null) {
                session.task().cancel();
            }
            BackConfig config = configSupplier.get();
            sendPrefixedMessage(player, config.messages().warmupCancelledMove());
        }
    }

    public void handlePlayerDamage(Player player) {
        UUID uuid = player.getUniqueId();
        WarmupSession session = activeWarmups.remove(uuid);
        if (session != null) {
            if (session.task() != null) {
                session.task().cancel();
            }
            BackConfig config = configSupplier.get();
            sendPrefixedMessage(player, config.messages().warmupCancelledDamage());
        }
    }

    public void handlePlayerQuit(UUID uuid) {
        cancelWarmup(uuid);
    }

    public void clear() {
        for (WarmupSession session : activeWarmups.values()) {
            if (session.task() != null) {
                session.task().cancel();
            }
        }
        activeWarmups.clear();
    }

    private void sendPrefixedMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) return;
        BackConfig config = configSupplier.get();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        player.sendMessage(miniMessage.deserialize(
            MessageFormatter.toMiniMessage(config.messages().prefix() + message),
            prefixResolver
        ));
    }
}
