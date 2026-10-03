package com.lunatech.tpcore.module.back.service.impl;

import com.lunatech.tpcore.config.model.BackConfig;
import com.lunatech.tpcore.module.back.economy.BackEconomyService;
import com.lunatech.tpcore.module.back.economy.impl.NoOpBackEconomyService;
import com.lunatech.tpcore.module.back.model.BackLocation;
import com.lunatech.tpcore.util.MessageFormatter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class BackWarmupManager {

    private final Plugin plugin;
    private final Supplier<BackConfig> configSupplier;
    private final BackEconomyService economyService;
    private final BackWarmupRenderer renderer;
    private final MiniMessage miniMessage;
    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();

    private static final class ActiveWarmup {
        private final UUID playerId;
        private final String worldName;
        private final double startX;
        private final double startY;
        private final double startZ;
        private final int totalSeconds;
        private int remainingSeconds;
        private final BackLocation targetLocation;
        private final double paidCost;
        private BossBar bossBar;
        private ScheduledTask task;

        ActiveWarmup(UUID playerId, String worldName, double startX, double startY, double startZ, int totalSeconds, BackLocation targetLocation, double paidCost) {
            this.playerId = playerId;
            this.worldName = worldName;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.totalSeconds = totalSeconds;
            this.remainingSeconds = totalSeconds;
            this.targetLocation = targetLocation;
            this.paidCost = paidCost;
        }

        boolean hasMoved(Location currentLoc) {
            if (currentLoc == null || currentLoc.getWorld() == null) {
                return true;
            }
            if (!this.worldName.equals(currentLoc.getWorld().getName())) {
                return true;
            }
            double dx = currentLoc.getX() - this.startX;
            double dy = currentLoc.getY() - this.startY;
            double dz = currentLoc.getZ() - this.startZ;
            return (dx * dx + dy * dy + dz * dz) > 0.25;
        }
    }

    public BackWarmupManager(Plugin plugin, Supplier<BackConfig> configSupplier, BackWarmupRenderer renderer, MiniMessage miniMessage) {
        this(plugin, configSupplier, new NoOpBackEconomyService(), renderer, miniMessage);
    }

    public BackWarmupManager(Plugin plugin, Supplier<BackConfig> configSupplier, BackEconomyService economyService, BackWarmupRenderer renderer, MiniMessage miniMessage) {
        this.plugin = plugin;
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.economyService = Objects.requireNonNull(economyService, "economyService cannot be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer cannot be null");
        this.miniMessage = Objects.requireNonNull(miniMessage, "miniMessage cannot be null");
    }

    public boolean hasActiveWarmup(UUID uuid) {
        return uuid != null && activeWarmups.containsKey(uuid);
    }

    public void startWarmup(Player player, BackLocation targetBackLoc, int warmupSeconds, double paidCost, Runnable onComplete, Runnable onCancel) {
        UUID uuid = player.getUniqueId();
        this.cancelWarmup(uuid, null);

        BackConfig config = configSupplier.get();
        String rawWarmup = config.messages().warmupStart();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        TagResolver secondsResolver = Placeholder.unparsed("seconds", String.valueOf(warmupSeconds));

        player.sendMessage(miniMessage.deserialize(
            MessageFormatter.toMiniMessage(rawWarmup),
            TagResolver.resolver(prefixResolver, secondsResolver)
        ));

        Location loc = player.getLocation();
        ActiveWarmup warmup = new ActiveWarmup(
            uuid,
            loc.getWorld() != null ? loc.getWorld().getName() : "",
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            warmupSeconds,
            targetBackLoc,
            paidCost
        );

        BossBar bossBar = this.renderer.createBossBar(config, warmupSeconds);
        if (bossBar != null) {
            warmup.bossBar = bossBar;
            player.showBossBar(bossBar);
        }

        this.renderer.updateWarmupFeedback(player, config, warmupSeconds, warmupSeconds);

        if (this.plugin != null) {
            ScheduledTask task = player.getScheduler().runAtFixedRate(
                this.plugin,
                scheduledTask -> {
                    if (!player.isOnline()) {
                        this.cancelWarmup(uuid, null);
                        if (onCancel != null) onCancel.run();
                        return;
                    }

                    warmup.remainingSeconds--;

                    if (warmup.remainingSeconds <= 0) {
                        ActiveWarmup completed = this.activeWarmups.remove(uuid);
                        if (completed != null) {
                            if (completed.task != null) {
                                completed.task.cancel();
                            }
                            if (completed.bossBar != null) {
                                player.hideBossBar(completed.bossBar);
                            }
                            if (config.enableSounds()) {
                                this.renderer.playSound(
                                    player,
                                    config.teleportSound(),
                                    (float) config.teleportSoundVolume(),
                                    (float) config.teleportSoundPitch()
                                );
                            }
                            if (onComplete != null) {
                                onComplete.run();
                            }
                        }
                    } else {
                        this.renderer.updateBossBar(warmup.bossBar, config, warmup.remainingSeconds, warmup.totalSeconds);
                        this.renderer.updateWarmupFeedback(player, config, warmup.remainingSeconds, warmup.totalSeconds);
                    }
                },
                () -> {
                    this.cancelWarmup(uuid, null);
                    if (onCancel != null) onCancel.run();
                },
                20L,
                20L
            );
            warmup.task = task;
            this.activeWarmups.put(uuid, warmup);
        } else if (onComplete != null) {
            onComplete.run();
        }
    }

    public void cancelWarmup(UUID uuid, Consumer<Player> onCancelled) {
        ActiveWarmup warmup = this.activeWarmups.remove(uuid);
        if (warmup != null) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
            Player player = Bukkit.getPlayer(uuid);
            BackConfig config = this.configSupplier.get();
            if (player != null && player.isOnline()) {
                if (warmup.bossBar != null) {
                    player.hideBossBar(warmup.bossBar);
                }
                if (config.enableSounds()) {
                    this.renderer.playSound(
                        player,
                        config.cancelSound(),
                        (float) config.cancelSoundVolume(),
                        (float) config.cancelSoundPitch()
                    );
                }
                if (onCancelled != null) {
                    onCancelled.accept(player);
                }
            }
            if (warmup.paidCost > 0.0 && config.refundOnCancel()) {
                this.economyService.processRefund(Bukkit.getOfflinePlayer(uuid), warmup.paidCost);
            }
        }
    }

    public void cancelWarmup(UUID uuid) {
        cancelWarmup(uuid, null);
    }

    public void handlePlayerMove(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        ActiveWarmup warmup = this.activeWarmups.get(uuid);
        if (warmup != null && warmup.hasMoved(player.getLocation())) {
            cancelWarmup(uuid, p -> sendPrefixedMessage(p, configSupplier.get().messages().warmupCancelledMove()));
        }
    }

    public void handlePlayerDamage(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (this.activeWarmups.containsKey(uuid)) {
            cancelWarmup(uuid, p -> sendPrefixedMessage(p, configSupplier.get().messages().warmupCancelledDamage()));
        }
    }

    public void handlePlayerQuit(UUID uuid) {
        cancelWarmup(uuid);
    }

    public void clear() {
        BackConfig config = this.configSupplier.get();
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
            Player player = Bukkit.getPlayer(warmup.playerId);
            if (player != null && player.isOnline() && warmup.bossBar != null) {
                player.hideBossBar(warmup.bossBar);
            }
            if (warmup.paidCost > 0.0 && config.refundOnCancel()) {
                this.economyService.processRefund(Bukkit.getOfflinePlayer(warmup.playerId), warmup.paidCost);
            }
        }
        this.activeWarmups.clear();
        this.renderer.clear();
    }

    private void sendPrefixedMessage(Player player, String message) {
        if (player == null || message == null || message.isBlank()) return;
        BackConfig config = configSupplier.get();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        player.sendMessage(miniMessage.deserialize(
            MessageFormatter.toMiniMessage(message),
            prefixResolver
        ));
    }
}
