package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultTpaService implements TpaService {

    private final JavaPlugin plugin;
    private final TpaRepository repository;
    private final AtomicReference<TpaConfig> configRef;
    private final MiniMessage miniMessage;

    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();
    private final ScheduledTask sweeperTask;

    private record ActiveWarmup(
        UUID teleportingPlayerId,
        String worldName,
        double startX,
        double startY,
        double startZ,
        ScheduledTask task
    ) {
        public boolean hasMoved(Location currentLoc) {
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

    public DefaultTpaService(JavaPlugin plugin, TpaRepository repository, TpaConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.configRef = new AtomicReference<>(config);
        this.miniMessage = MiniMessage.miniMessage();
        this.sweeperTask = this.startExpirationSweeper();
    }

    private TpaConfig config() {
        return this.configRef.get();
    }

    @Override
    public void updateConfig(TpaConfig newConfig) {
        if (newConfig != null) {
            this.configRef.set(newConfig);
        }
    }

    private ScheduledTask startExpirationSweeper() {
        return this.plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            this.plugin,
            task -> {
                for (TpaRequest request : this.repository.getAllRequests()) {
                    if (request.isExpired(this.config().requestTimeoutSeconds())) {
                        this.repository.removeRequest(request.targetId(), request.senderId());

                        Player sender = Bukkit.getPlayer(request.senderId());
                        if (sender != null && sender.isOnline()) {
                            Player target = Bukkit.getPlayer(request.targetId());
                            sender.getScheduler().run(
                                this.plugin,
                                t -> {
                                    this.closeConfirmationMenuIfOpen(sender);
                                    this.sendMessage(
                                        sender,
                                        this.config().messages().requestExpired(),
                                        "player", (target != null) ? target.getName() : "Player"
                                    );
                                },
                                null
                            );
                        }

                        Player target = Bukkit.getPlayer(request.targetId());
                        if (target != null && target.isOnline()) {
                            Player senderPlayer = Bukkit.getPlayer(request.senderId());
                            target.getScheduler().run(
                                this.plugin,
                                t -> {
                                    this.closeConfirmationMenuIfOpen(target);
                                    this.sendMessage(
                                        target,
                                        this.config().messages().requestExpired(),
                                        "player", (senderPlayer != null) ? senderPlayer.getName() : "Player"
                                    );
                                },
                                null
                            );
                        }
                    }
                }
            },
            100L,
            100L
        );
    }

    @Override
    public void sendRequest(Player sender, Player target, TpaType type) {
        if (!this.config().allowSelfTpa() && sender.getUniqueId().equals(target.getUniqueId())) {
            this.sendMessage(sender, this.config().messages().rejectSelfTpa());
            return;
        }

        if (this.repository.isTpaToggledOff(target.getUniqueId())) {
            this.sendMessage(
                sender,
                this.config().messages().targetToggledOff(),
                "target", target.getName()
            );
            return;
        }

        Optional<TpaRequest> existing = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId());
        if (existing.isPresent() && !existing.get().isExpired(this.config().requestTimeoutSeconds())) {
            this.sendMessage(
                sender,
                this.config().messages().alreadyHasPendingRequest(),
                "target", target.getName()
            );
            return;
        }

        TpaRequest request = new TpaRequest(
            sender.getUniqueId(),
            target.getUniqueId(),
            type,
            System.currentTimeMillis()
        );

        this.repository.addRequest(request);

        if (type == TpaType.TPA_TO) {
            this.sendMessage(
                sender,
                this.config().messages().senderTpaSent(),
                "target", target.getName(),
                "seconds", String.valueOf(this.config().requestTimeoutSeconds())
            );

            this.sendMessage(
                target,
                this.config().messages().targetTpaReceived(),
                "sender", sender.getName()
            );
        } else {
            this.sendMessage(
                sender,
                this.config().messages().senderTpaHereSent(),
                "target", target.getName(),
                "seconds", String.valueOf(this.config().requestTimeoutSeconds())
            );

            this.sendMessage(
                target,
                this.config().messages().targetTpaHereReceived(),
                "sender", sender.getName()
            );
        }
    }

    @Override
    public void acceptRequest(Player target, String optionalSenderName) {
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());

        if (incoming.isEmpty()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;

        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            Player sender = Bukkit.getPlayer(optionalSenderName);
            if (sender != null) {
                Optional<TpaRequest> opt = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId());
                if (opt.isPresent()) {
                    targetRequest = opt.get();
                }
            }
        } else if (incoming.size() == 1) {
            targetRequest = incoming.iterator().next();
        } else {
            this.sendMessage(target, this.config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest == null || targetRequest.isExpired(this.config().requestTimeoutSeconds())) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            if (targetRequest != null) {
                this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());
            }
            return;
        }

        this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());

        Player sender = Bukkit.getPlayer(targetRequest.senderId());
        if (sender == null || !sender.isOnline()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        this.sendMessage(
            target,
            this.config().messages().requestAcceptedTarget(),
            "sender", sender.getName()
        );

        this.sendMessage(
            sender,
            this.config().messages().requestAcceptedSender(),
            "target", target.getName()
        );

        Player teleportingPlayer = (targetRequest.type() == TpaType.TPA_TO) ? sender : target;
        Player destinationPlayer = (targetRequest.type() == TpaType.TPA_TO) ? target : sender;

        this.executeTeleportSequence(teleportingPlayer, destinationPlayer);
    }

    @Override
    public void denyRequest(Player target, String optionalSenderName) {
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
        if (incoming.isEmpty()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            Player sender = Bukkit.getPlayer(optionalSenderName);
            if (sender != null) {
                Optional<TpaRequest> opt = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId());
                if (opt.isPresent()) {
                    targetRequest = opt.get();
                }
            }
        } else {
            targetRequest = incoming.iterator().next();
        }

        if (targetRequest != null) {
            this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());
            Player sender = Bukkit.getPlayer(targetRequest.senderId());
            if (sender != null && sender.isOnline()) {
                this.sendMessage(
                    sender,
                    this.config().messages().requestDeniedSender(),
                    "target", target.getName()
                );
            }
            this.sendMessage(
                target,
                this.config().messages().requestDeniedTarget(),
                "sender", (sender != null) ? sender.getName() : "Player"
            );
        } else {
            this.sendMessage(target, this.config().messages().noPendingRequests());
        }
    }

    @Override
    public void cancelRequest(Player sender, String optionalTargetName) {
        Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(sender.getUniqueId());
        if (outgoing.isEmpty()) {
            this.sendMessage(sender, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalTargetName != null && !optionalTargetName.isBlank()) {
            Player target = Bukkit.getPlayer(optionalTargetName);
            if (target != null) {
                Optional<TpaRequest> opt = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId());
                if (opt.isPresent()) {
                    targetRequest = opt.get();
                }
            }
        } else {
            targetRequest = outgoing.iterator().next();
        }

        if (targetRequest != null) {
            this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());
            Player target = Bukkit.getPlayer(targetRequest.targetId());
            if (target != null && target.isOnline()) {
                this.sendMessage(
                    target,
                    this.config().messages().requestCancelledTarget(),
                    "sender", sender.getName()
                );
            }
            this.sendMessage(
                sender,
                this.config().messages().requestCancelledSender(),
                "target", (target != null) ? target.getName() : "Player"
            );
        } else {
            this.sendMessage(sender, this.config().messages().noPendingRequests());
        }
    }

    @Override
    public boolean toggleTpa(Player player) {
        boolean currentlyOff = this.repository.isTpaToggledOff(player.getUniqueId());
        boolean newStatus = !currentlyOff;
        this.repository.setTpaToggledOff(player.getUniqueId(), newStatus);

        if (newStatus) {
            this.sendMessage(player, this.config().messages().toggleOff());
        } else {
            this.sendMessage(player, this.config().messages().toggleOn());
        }
        return !newStatus;
    }

    @Override
    public Collection<TpaRequest> getPendingRequestsForTarget(Player target) {
        if (target == null) {
            return java.util.Collections.emptyList();
        }
        return this.repository.getIncomingRequests(target.getUniqueId());
    }

    @Override
    public TpaRequest findPendingRequest(Player target, String optionalSenderName) {
        if (target == null) {
            return null;
        }
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
        if (incoming.isEmpty()) {
            return null;
        }

        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            Player sender = Bukkit.getPlayer(optionalSenderName);
            if (sender != null) {
                Optional<TpaRequest> opt = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId());
                if (opt.isPresent() && !opt.get().isExpired(this.config().requestTimeoutSeconds())) {
                    return opt.get();
                }
            }
            return null;
        }

        if (incoming.size() == 1) {
            TpaRequest first = incoming.iterator().next();
            if (!first.isExpired(this.config().requestTimeoutSeconds())) {
                return first;
            }
        }

        return null;
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        this.repository.removeAllRequestsForPlayer(playerId);
        this.cancelWarmup(playerId, null);
    }

    @Override
    public void handlePlayerDamage(UUID playerId) {
        if (this.config().cancelOnDamage()) {
            this.cancelWarmup(playerId, this.config().messages().warmupCancelledDamage());
        }
    }

    @Override
    public void handlePlayerMove(Player player) {
        if (!this.config().cancelOnMove() || this.activeWarmups.isEmpty()) {
            return;
        }
        ActiveWarmup warmup = this.activeWarmups.get(player.getUniqueId());
        if (warmup != null && warmup.hasMoved(player.getLocation())) {
            this.cancelWarmup(player.getUniqueId(), this.config().messages().warmupCancelledMove());
        }
    }

    @Override
    public void handlePlayerTeleport(UUID playerId) {
        this.cancelWarmup(playerId, null);
    }

    @Override
    public void handlePlayerDeath(UUID playerId) {
        this.repository.removeAllRequestsForPlayer(playerId);
        this.cancelWarmup(playerId, null);
    }

    private void executeTeleportSequence(Player player, Player destinationPlayer) {
        if (player == null || !player.isOnline() || destinationPlayer == null || !destinationPlayer.isOnline()) {
            return;
        }

        int warmupSeconds = this.config().warmupSeconds();
        if (warmupSeconds <= 0 || player.hasPermission(Permissions.TPA_BYPASS_WARMUP)) {
            performFinalTeleport(player, destinationPlayer);
            return;
        }

        this.cancelWarmup(player.getUniqueId(), null);

        this.sendMessage(
            player,
            this.config().messages().warmupStart(),
            "seconds", String.valueOf(warmupSeconds)
        );

        Location currentLoc = player.getLocation();
        ScheduledTask task = player.getScheduler().runDelayed(
            this.plugin,
            scheduledTask -> {
                ActiveWarmup warmup = this.activeWarmups.remove(player.getUniqueId());
                if (warmup != null && player.isOnline() && destinationPlayer.isOnline()) {
                    performFinalTeleport(player, destinationPlayer);
                }
            },
            null,
            warmupSeconds * 20L
        );

        if (task != null) {
            this.activeWarmups.put(
                player.getUniqueId(),
                new ActiveWarmup(
                    player.getUniqueId(),
                    currentLoc.getWorld().getName(),
                    currentLoc.getX(),
                    currentLoc.getY(),
                    currentLoc.getZ(),
                    task
                )
            );
        }
    }

    private void performFinalTeleport(Player player, Player destinationPlayer) {
        if (player == null || !player.isOnline() || destinationPlayer == null || !destinationPlayer.isOnline()) {
            return;
        }

        destinationPlayer.getScheduler().run(
            this.plugin,
            task -> {
                if (!player.isOnline() || !destinationPlayer.isOnline()) {
                    return;
                }

                if (player.isInsideVehicle()) {
                    player.leaveVehicle();
                }

                Location rawTargetLoc = destinationPlayer.getLocation();
                Location finalTargetLoc = rawTargetLoc;

                if (this.config().requireSafeLocation()) {
                    Location safeLoc = TpaSafetyInspector.findSafeLocation(rawTargetLoc);
                    if (safeLoc == null) {
                        this.sendMessage(player, this.config().messages().unsafeDestination());
                        this.sendMessage(destinationPlayer, this.config().messages().unsafeDestination());
                        return;
                    }
                    finalTargetLoc = safeLoc;
                }

                player.teleportAsync(finalTargetLoc);
            },
            null
        );
    }

    private void cancelWarmup(UUID playerId, String cancelMessageTemplate) {
        ActiveWarmup warmup = this.activeWarmups.remove(playerId);
        if (warmup != null) {
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
            if (cancelMessageTemplate != null) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    this.sendMessage(player, cancelMessageTemplate);
                }
            }
        }
    }

    private void closeConfirmationMenuIfOpen(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory().getHolder() instanceof com.lunatech.tpcore.module.tpa.gui.TpaConfirmationHolder) {
                player.closeInventory();
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method closeDialogMethod = player.getClass().getMethod("closeDialog");
            closeDialogMethod.setAccessible(true);
            closeDialogMethod.invoke(player);
        } catch (Throwable ignored) {
        }
    }

    private void sendMessage(Player player, String template) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        player.sendMessage(this.miniMessage.deserialize(template, prefixResolver));
    }

    private void sendMessage(Player player, String template, String key, String value) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safeValue = value != null ? value : "";
        String processed = template.replace("<" + key + ">", safeValue);
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        TagResolver valueResolver = Placeholder.unparsed(key, safeValue);
        TagResolver combined = TagResolver.resolver(prefixResolver, valueResolver);
        player.sendMessage(this.miniMessage.deserialize(processed, combined));
    }

    private void sendMessage(Player player, String template, String key1, String value1, String key2, String value2) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safe1 = value1 != null ? value1 : "";
        String safe2 = value2 != null ? value2 : "";
        String processed = template.replace("<" + key1 + ">", safe1).replace("<" + key2 + ">", safe2);
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        TagResolver combined = TagResolver.resolver(
            prefixResolver,
            Placeholder.unparsed(key1, safe1),
            Placeholder.unparsed(key2, safe2)
        );
        player.sendMessage(this.miniMessage.deserialize(processed, combined));
    }

    @Override
    public void shutdown() {
        if (this.sweeperTask != null) {
            this.sweeperTask.cancel();
        }
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
        }
        this.activeWarmups.clear();
        this.repository.clear();
    }
}
