package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Messages;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultTpaService implements TpaService {

    private final JavaPlugin plugin;
    private final TpaRepository repository;
    private final TpaConfig config;
    private final MiniMessage miniMessage;

    // Active warmup tracking: Player UUID -> WarmupTask
    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();
    private final io.papermc.paper.threadedregions.scheduler.ScheduledTask sweeperTask;

    private record ActiveWarmup(
        UUID teleportingPlayerId,
        Location startLocation
    ) {}

    public DefaultTpaService(JavaPlugin plugin, TpaRepository repository, TpaConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
        this.miniMessage = MiniMessage.miniMessage();
        this.sweeperTask = this.startExpirationSweeper();
    }

    private io.papermc.paper.threadedregions.scheduler.ScheduledTask startExpirationSweeper() {
        return this.plugin.getServer().getAsyncScheduler().runAtFixedRate(
            this.plugin,
            task -> {
                for (TpaRequest request : this.repository.getAllRequests()) {
                    if (request.isExpired(this.config.requestTimeoutSeconds())) {
                        this.repository.removeRequest(request.targetId(), request.senderId());
                        
                        Player sender = Bukkit.getPlayer(request.senderId());
                        if (sender != null && sender.isOnline()) {
                            Player target = Bukkit.getPlayer(request.targetId());
                            sender.sendMessage(this.miniMessage.deserialize(
                                Messages.REQUEST_EXPIRED,
                                Placeholder.unparsed("player", (target != null) ? target.getName() : "Player")
                            ));
                        }
                        
                        Player target = Bukkit.getPlayer(request.targetId());
                        if (target != null && target.isOnline()) {
                            Player senderPlayer = Bukkit.getPlayer(request.senderId());
                            target.sendMessage(this.miniMessage.deserialize(
                                Messages.REQUEST_EXPIRED,
                                Placeholder.unparsed("player", (senderPlayer != null) ? senderPlayer.getName() : "Player")
                            ));
                        }
                    }
                }
            },
            5L,
            5L,
            java.util.concurrent.TimeUnit.SECONDS
        );
    }

    @Override
    public void sendRequest(Player sender, Player target, TpaType type) {
        if (!this.config.allowSelfTpa() && sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(this.miniMessage.deserialize(Messages.REJECT_SELF_TPA));
            return;
        }

        if (this.repository.isTpaToggledOff(target.getUniqueId())) {
            sender.sendMessage(this.miniMessage.deserialize(
                Messages.TARGET_TOGGLED_OFF,
                Placeholder.unparsed("target", target.getName())
            ));
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
            sender.sendMessage(this.miniMessage.deserialize(
                Messages.SENDER_TPA_SENT,
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("seconds", String.valueOf(this.config.requestTimeoutSeconds()))
            ));

            target.sendMessage(this.miniMessage.deserialize(
                Messages.TARGET_TPA_RECEIVED,
                Placeholder.unparsed("sender", sender.getName())
            ));
        } else {
            sender.sendMessage(this.miniMessage.deserialize(
                Messages.SENDER_TPAHERE_SENT,
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("seconds", String.valueOf(this.config.requestTimeoutSeconds()))
            ));

            target.sendMessage(this.miniMessage.deserialize(
                Messages.TARGET_TPAHERE_RECEIVED,
                Placeholder.unparsed("sender", sender.getName())
            ));
        }
    }

    @Override
    public void acceptRequest(Player target, String optionalSenderName) {
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());

        if (incoming.isEmpty()) {
            target.sendMessage(this.miniMessage.deserialize(Messages.NO_PENDING_REQUESTS));
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
            target.sendMessage(this.miniMessage.deserialize(Messages.MULTIPLE_PENDING_REQUESTS));
            return;
        }

        if (targetRequest == null || targetRequest.isExpired(this.config.requestTimeoutSeconds())) {
            target.sendMessage(this.miniMessage.deserialize(Messages.NO_PENDING_REQUESTS));
            if (targetRequest != null) {
                this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());
            }
            return;
        }

        this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());

        Player sender = Bukkit.getPlayer(targetRequest.senderId());
        if (sender == null || !sender.isOnline()) {
            target.sendMessage(this.miniMessage.deserialize(Messages.NO_PENDING_REQUESTS));
            return;
        }

        target.sendMessage(this.miniMessage.deserialize(
            Messages.REQUEST_ACCEPTED_TARGET,
            Placeholder.unparsed("sender", sender.getName())
        ));

        sender.sendMessage(this.miniMessage.deserialize(
            Messages.REQUEST_ACCEPTED_SENDER,
            Placeholder.unparsed("target", target.getName())
        ));

        Player teleportingPlayer = (targetRequest.type() == TpaType.TPA_TO) ? sender : target;
        Player destinationPlayer = (targetRequest.type() == TpaType.TPA_TO) ? target : sender;

        this.executeTeleportSequence(teleportingPlayer, destinationPlayer.getLocation());
    }

    @Override
    public void denyRequest(Player target, String optionalSenderName) {
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
        if (incoming.isEmpty()) {
            target.sendMessage(this.miniMessage.deserialize(Messages.NO_PENDING_REQUESTS));
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
                sender.sendMessage(this.miniMessage.deserialize(
                    Messages.REQUEST_DENIED_SENDER,
                    Placeholder.unparsed("target", target.getName())
                ));
            }
            target.sendMessage(this.miniMessage.deserialize(
                Messages.REQUEST_DENIED_TARGET,
                Placeholder.unparsed("sender", (sender != null) ? sender.getName() : "Player")
            ));
        } else {
            target.sendMessage(this.miniMessage.deserialize(Messages.NO_PENDING_REQUESTS));
        }
    }

    @Override
    public void cancelRequest(Player sender, String optionalTargetName) {
        Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(sender.getUniqueId());
        if (outgoing.isEmpty()) {
            sender.sendMessage(this.miniMessage.deserialize(Messages.NO_PENDING_REQUESTS));
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
                target.sendMessage(this.miniMessage.deserialize(
                    Messages.REQUEST_CANCELLED_TARGET,
                    Placeholder.unparsed("sender", sender.getName())
                ));
            }
            sender.sendMessage(this.miniMessage.deserialize(
                Messages.REQUEST_CANCELLED_SENDER,
                Placeholder.unparsed("target", (target != null) ? target.getName() : "Player")
            ));
        } else {
            sender.sendMessage(this.miniMessage.deserialize(Messages.NO_PENDING_REQUESTS));
        }
    }

    @Override
    public boolean toggleTpa(Player player) {
        boolean currentlyOff = this.repository.isTpaToggledOff(player.getUniqueId());
        boolean newStatus = !currentlyOff;
        this.repository.setTpaToggledOff(player.getUniqueId(), newStatus);

        if (newStatus) {
            player.sendMessage(this.miniMessage.deserialize(Messages.TOGGLE_OFF));
        } else {
            player.sendMessage(this.miniMessage.deserialize(Messages.TOGGLE_ON));
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
        if (this.config.cancelOnDamage()) {
            this.cancelWarmup(playerId, Messages.WARMUP_CANCELLED_DAMAGE);
        }
    }

    @Override
    public void handlePlayerMove(Player player) {
        if (!this.config.cancelOnMove()) {
            return;
        }
        ActiveWarmup warmup = this.activeWarmups.get(player.getUniqueId());
        if (warmup != null) {
            if (warmup.startLocation().distanceSquared(player.getLocation()) > 0.25) {
                this.cancelWarmup(player.getUniqueId(), Messages.WARMUP_CANCELLED_MOVE);
            }
        }
    }

    private void executeTeleportSequence(Player player, Location targetLocation) {
        int warmupSeconds = this.config.warmupSeconds();
        if (warmupSeconds <= 0 || player.hasPermission(Permissions.TPA_BYPASS_WARMUP)) {
            player.teleportAsync(targetLocation);
            return;
        }

        player.sendMessage(this.miniMessage.deserialize(
            Messages.WARMUP_START,
            Placeholder.unparsed("seconds", String.valueOf(warmupSeconds))
        ));

        // Use Folia & Paper native EntityScheduler to guarantee thread safety
        player.getScheduler().runDelayed(
            this.plugin,
            task -> {
                this.activeWarmups.remove(player.getUniqueId());
                if (player.isOnline()) {
                    player.teleportAsync(targetLocation);
                }
            },
            null,
            warmupSeconds * 20L
        );

        this.activeWarmups.put(
            player.getUniqueId(),
            new ActiveWarmup(player.getUniqueId(), player.getLocation().clone())
        );
    }

    private void cancelWarmup(UUID playerId, String cancelMessageKey) {
        ActiveWarmup warmup = this.activeWarmups.remove(playerId);
        if (warmup != null) {
            if (cancelMessageKey != null) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    player.sendMessage(this.miniMessage.deserialize(cancelMessageKey));
                }
            }
        }
    }

    @Override
    public void shutdown() {
        if (this.sweeperTask != null) {
            this.sweeperTask.cancel();
        }
        this.activeWarmups.clear();
        this.repository.clear();
    }
}
