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
import java.util.concurrent.TimeUnit;
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
        return this.plugin.getServer().getAsyncScheduler().runAtFixedRate(
            this.plugin,
            task -> {
                for (TpaRequest request : this.repository.getAllRequests()) {
                    if (request.isExpired(this.config().requestTimeoutSeconds())) {
                        this.repository.removeRequest(request.targetId(), request.senderId());

                        Player sender = Bukkit.getPlayer(request.senderId());
                        if (sender != null && sender.isOnline()) {
                            Player target = Bukkit.getPlayer(request.targetId());
                            this.sendMessage(
                                sender,
                                this.config().messages().requestExpired(),
                                Placeholder.unparsed("player", (target != null) ? target.getName() : "Player")
                            );
                        }

                        Player target = Bukkit.getPlayer(request.targetId());
                        if (target != null && target.isOnline()) {
                            Player senderPlayer = Bukkit.getPlayer(request.senderId());
                            this.sendMessage(
                                target,
                                this.config().messages().requestExpired(),
                                Placeholder.unparsed("player", (senderPlayer != null) ? senderPlayer.getName() : "Player")
                            );
                        }
                    }
                }
            },
            5L,
            5L,
            TimeUnit.SECONDS
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
                Placeholder.unparsed("target", target.getName())
            );
            return;
        }

        Optional<TpaRequest> existing = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId());
        if (existing.isPresent() && !existing.get().isExpired(this.config().requestTimeoutSeconds())) {
            this.sendMessage(
                sender,
                this.config().messages().alreadyHasPendingRequest(),
                Placeholder.unparsed("target", target.getName())
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
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("seconds", String.valueOf(this.config().requestTimeoutSeconds()))
            );

            this.sendMessage(
                target,
                this.config().messages().targetTpaReceived(),
                Placeholder.unparsed("sender", sender.getName())
            );
        } else {
            this.sendMessage(
                sender,
                this.config().messages().senderTpaHereSent(),
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("seconds", String.valueOf(this.config().requestTimeoutSeconds()))
            );

            this.sendMessage(
                target,
                this.config().messages().targetTpaHereReceived(),
                Placeholder.unparsed("sender", sender.getName())
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
            Placeholder.unparsed("sender", sender.getName())
        );

        this.sendMessage(
            sender,
            this.config().messages().requestAcceptedSender(),
            Placeholder.unparsed("target", target.getName())
        );

        Player teleportingPlayer = (targetRequest.type() == TpaType.TPA_TO) ? sender : target;
        Player destinationPlayer = (targetRequest.type() == TpaType.TPA_TO) ? target : sender;

        this.executeTeleportSequence(teleportingPlayer, destinationPlayer.getLocation());
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
                    Placeholder.unparsed("target", target.getName())
                );
            }
            this.sendMessage(
                target,
                this.config().messages().requestDeniedTarget(),
                Placeholder.unparsed("sender", (sender != null) ? sender.getName() : "Player")
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
                    Placeholder.unparsed("sender", sender.getName())
                );
            }
            this.sendMessage(
                sender,
                this.config().messages().requestCancelledSender(),
                Placeholder.unparsed("target", (target != null) ? target.getName() : "Player")
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

    private void executeTeleportSequence(Player player, Location targetLocation) {
        int warmupSeconds = this.config().warmupSeconds();
        if (warmupSeconds <= 0 || player.hasPermission(Permissions.TPA_BYPASS_WARMUP)) {
            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }
            player.teleportAsync(targetLocation);
            return;
        }

        this.cancelWarmup(player.getUniqueId(), null);

        this.sendMessage(
            player,
            this.config().messages().warmupStart(),
            Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
        );

        Location currentLoc = player.getLocation();
        ScheduledTask task = player.getScheduler().runDelayed(
            this.plugin,
            scheduledTask -> {
                ActiveWarmup warmup = this.activeWarmups.remove(player.getUniqueId());
                if (warmup != null && player.isOnline()) {
                    if (player.isInsideVehicle()) {
                        player.leaveVehicle();
                    }
                    player.teleportAsync(targetLocation);
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

    private void sendMessage(Player player, String template, TagResolver... resolvers) {
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        TagResolver combined = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));
        player.sendMessage(this.miniMessage.deserialize(template, combined));
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
