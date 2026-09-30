package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.economy.TpaEconomyService;
import com.lunatech.tpcore.module.tpa.economy.impl.NoOpTpaEconomyService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Facade implementing TpaService by coordinating modularized TPA subcomponents.
 */
public final class DefaultTpaService implements TpaService {

    private final JavaPlugin plugin;
    private final TpaRepository repository;
    private final AtomicReference<TpaConfig> configRef;
    private final TpaEconomyService economyService;

    private final TpaMessenger messenger;
    private final TpaProtectionManager protectionManager;
    private final TpaEscrowManager escrowManager;
    private final TpaTeleportExecutor teleportExecutor;
    private final TpaWarmupManager warmupManager;
    private final TpaUserSettingsManager userSettingsManager;
    private final TpaRequestManager requestManager;
    private final TpaSendRequestHandler sendRequestHandler;

    public DefaultTpaService(JavaPlugin plugin, TpaRepository repository, TpaConfig config) {
        this(plugin, repository, config, new NoOpTpaEconomyService());
    }

    public DefaultTpaService(JavaPlugin plugin, TpaRepository repository, TpaConfig config, TpaEconomyService economyService) {
        this.plugin = plugin;
        this.repository = repository;
        this.configRef = new AtomicReference<>(config);
        this.economyService = (economyService != null) ? economyService : new NoOpTpaEconomyService();

        Supplier<TpaConfig> configSupplier = this::config;
        this.messenger = new TpaMessenger(configSupplier);
        this.protectionManager = new TpaProtectionManager(plugin, configSupplier, this.messenger);
        this.escrowManager = new TpaEscrowManager(plugin, configSupplier, repository, this.economyService, this.messenger);
        this.teleportExecutor = new TpaTeleportExecutor(plugin, configSupplier, repository, this.economyService, this.messenger, this.protectionManager, this.escrowManager);
        this.warmupManager = new TpaWarmupManager(plugin, configSupplier, repository, this.economyService, this.messenger, this.escrowManager, this.teleportExecutor);
        this.userSettingsManager = new TpaUserSettingsManager(plugin, configSupplier, repository, this.messenger, this.escrowManager, this.warmupManager);
        this.requestManager = new TpaRequestManager(plugin, configSupplier, repository, this.economyService, this.messenger, this.escrowManager, this.warmupManager);
        this.sendRequestHandler = new TpaSendRequestHandler(plugin, configSupplier, repository, this.economyService, this.messenger, this.escrowManager, this.warmupManager, (target, senderName) -> this.requestManager.acceptRequestInternal(target, senderName, false));

        this.loadOnlinePlayersSettings();
    }

    private TpaConfig config() {
        return this.configRef.get();
    }

    private void loadOnlinePlayersSettings() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null && player.isOnline()) {
                this.handlePlayerJoin(player);
            }
        }
    }

    @Override
    public void updateConfig(TpaConfig newConfig) {
        if (newConfig != null) {
            this.configRef.set(newConfig);
            this.messenger.clear();
        }
    }

    @Override
    public void sendRequest(Player sender, Player target, TpaType type) {
        this.sendRequestHandler.sendRequest(sender, target, type);
    }

    @Override
    public void sendRequest(Player sender, UUID targetId, TpaType type) {
        this.sendRequestHandler.sendRequest(sender, targetId, type);
    }

    @Override
    public void sendBulkRequests(Player sender, List<Player> targets, TpaType type) {
        this.sendRequestHandler.sendBulkRequests(sender, targets, type);
    }

    @Override
    public void acceptRequest(Player target, String optionalSenderName) {
        this.requestManager.acceptRequest(target, optionalSenderName);
    }

    @Override
    public void denyRequest(Player target, String optionalSenderName) {
        this.requestManager.denyRequest(target, optionalSenderName);
    }

    @Override
    public void cancelRequest(Player sender, String optionalTargetName) {
        this.requestManager.cancelRequest(sender, optionalTargetName);
    }

    @Override
    public boolean toggleTpa(Player player) {
        return this.userSettingsManager.toggleTpa(player);
    }

    @Override
    public boolean toggleAutoAccept(Player player) {
        return this.userSettingsManager.toggleAutoAccept(player, p -> this.requestManager.acceptRequestInternal(p, null, false));
    }

    @Override
    public void blockPlayer(Player player, String targetName) {
        this.userSettingsManager.blockPlayer(player, targetName);
    }

    @Override
    public void unblockPlayer(Player player, String targetName) {
        this.userSettingsManager.unblockPlayer(player, targetName);
    }

    @Override
    public void listBlockedPlayers(Player player) {
        this.userSettingsManager.listBlockedPlayers(player);
    }

    @Override
    public Set<UUID> getBlockedPlayers(Player player) {
        return this.userSettingsManager.getBlockedPlayers(player);
    }

    @Override
    public Collection<TpaRequest> getPendingRequestsForTarget(Player target) {
        return this.requestManager.getPendingRequestsForTarget(target);
    }

    @Override
    public Collection<TpaRequest> getOutgoingRequestsForSender(Player sender) {
        return this.requestManager.getOutgoingRequestsForSender(sender);
    }

    @Override
    public TpaRequest findPendingRequest(Player target, String optionalSenderName) {
        return this.requestManager.findPendingRequest(target, optionalSenderName);
    }

    @Override
    public void handlePlayerJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        this.userSettingsManager.loadUserSettingsFromPdc(player);
        this.escrowManager.claimPendingRefunds(player);
    }

    @Override
    public void handlePlayerQuit(Player player) {
        if (player != null) {
            this.userSettingsManager.saveUserSettingsToPdc(player);
            this.handlePlayerQuit(player.getUniqueId());
        }
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        if (playerId == null) {
            return;
        }
        this.protectionManager.evict(playerId);
        this.escrowManager.evict(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            this.userSettingsManager.saveUserSettingsToPdc(player);
        }

        String quitName = (player != null && player.getName() != null) ? player.getName() : null;
        if (quitName == null) {
            OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(playerId);
            quitName = (op != null && op.getName() != null) ? op.getName() : "Player";
        }
        final String displayName = quitName;

        Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(playerId);
        if (outgoing != null && !outgoing.isEmpty()) {
            for (TpaRequest req : outgoing) {
                this.escrowManager.refundRequestSenderIfCharged(req, "Sender disconnected");
                Player target = Bukkit.getPlayer(req.targetId());
                if (target != null && target.isOnline()) {
                    target.getScheduler().run(
                        this.plugin,
                        t -> {
                            TpaMenuCloser.closeConfirmationMenuIfOpen(target, playerId);
                            this.messenger.sendMessage(
                                target,
                                config().messages().requestCancelledTarget(),
                                "sender", displayName
                            );
                        },
                        null
                    );
                }
            }
        }

        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(playerId);
        if (incoming != null && !incoming.isEmpty()) {
            for (TpaRequest req : incoming) {
                this.escrowManager.refundRequestSenderIfCharged(req, "Target disconnected");
                this.repository.setCooldownEnd(req.senderId(), 0L);
                Player sender = Bukkit.getPlayer(req.senderId());
                if (sender != null && sender.isOnline()) {
                    sender.getScheduler().run(
                        this.plugin,
                        t -> {
                            TpaMenuCloser.closeConfirmationMenuIfOpen(sender, playerId);
                            this.messenger.sendMessage(
                                sender,
                                config().messages().requestCancelledSender(),
                                "target", displayName
                            );
                        },
                        null
                    );
                }
            }
        }

        this.repository.removeAllRequestsForPlayer(playerId);
        this.repository.setUserSettings(playerId, null);
        this.repository.setCooldownEnd(playerId, 0L);
        this.warmupManager.cancelWarmup(playerId, null);
        this.warmupManager.cancelWarmupsForDestination(playerId, config().messages().playerNotOnline());
    }

    @Override
    public void handlePlayerDamage(UUID playerId) {
        this.warmupManager.handlePlayerDamage(playerId);
    }

    @Override
    public void handlePlayerMove(Player player) {
        this.warmupManager.handlePlayerMove(player);
    }

    @Override
    public void handlePlayerMove(Player player, Location to) {
        this.warmupManager.handlePlayerMove(player, to);
    }

    @Override
    public void handlePlayerTeleport(UUID playerId) {
        this.warmupManager.cancelWarmup(playerId, null);
        this.warmupManager.cancelWarmupsForDestination(playerId, config().messages().requestCancelledTarget());
    }

    @Override
    public void handlePlayerDeath(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Player deadPlayer = Bukkit.getPlayer(playerId);
        String deadName = (deadPlayer != null && deadPlayer.getName() != null) ? deadPlayer.getName() : null;
        if (deadName == null) {
            OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(playerId);
            deadName = (op != null && op.getName() != null) ? op.getName() : "Player";
        }
        final String displayName = deadName;

        Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(playerId);
        if (outgoing != null && !outgoing.isEmpty()) {
            for (TpaRequest req : outgoing) {
                this.escrowManager.refundRequestSenderIfCharged(req, "Sender died");
                Player target = Bukkit.getPlayer(req.targetId());
                if (target != null && target.isOnline()) {
                    target.getScheduler().run(
                        this.plugin,
                        t -> TpaMenuCloser.closeConfirmationMenuIfOpen(target, playerId),
                        null
                    );
                }
            }
        }

        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(playerId);
        if (incoming != null && !incoming.isEmpty()) {
            for (TpaRequest req : incoming) {
                this.escrowManager.refundRequestSenderIfCharged(req, "Target died");
                this.repository.setCooldownEnd(req.senderId(), 0L);
                Player sender = Bukkit.getPlayer(req.senderId());
                if (sender != null && sender.isOnline()) {
                    sender.getScheduler().run(
                        this.plugin,
                        t -> {
                            TpaMenuCloser.closeConfirmationMenuIfOpen(sender, playerId);
                            this.messenger.sendMessage(
                                sender,
                                config().messages().requestCancelledSender(),
                                "target", displayName
                            );
                        },
                        null
                    );
                }
            }
        }

        this.repository.removeAllRequestsForPlayer(playerId);
        this.repository.setCooldownEnd(playerId, 0L);
        this.protectionManager.evict(playerId);
        this.warmupManager.cancelWarmup(playerId, null);
        this.warmupManager.cancelWarmupsForDestination(playerId, config().messages().requestCancelledTarget());
        if (deadPlayer != null && deadPlayer.isOnline()) {
            deadPlayer.getScheduler().run(
                this.plugin,
                t -> TpaMenuCloser.closeConfirmationMenuIfOpen(deadPlayer, null),
                null
            );
        }
    }

    @Override
    public void grantTeleportProtection(Player player) {
        this.protectionManager.grantTeleportProtection(player);
    }

    @Override
    public boolean hasTeleportProtection(UUID playerId) {
        return this.protectionManager.hasTeleportProtection(playerId);
    }

    @Override
    public void stripTeleportProtection(UUID playerId) {
        this.protectionManager.stripTeleportProtection(playerId);
    }

    @Override
    public boolean handlePlayerProtectionDamage(Player victim, Player attacker, boolean isPvp) {
        return this.protectionManager.handlePlayerProtectionDamage(victim, attacker, isPvp);
    }

    @Override
    public boolean hasActiveWarmups() {
        return this.warmupManager.hasActiveWarmups();
    }

    @Override
    public long getTeleportProtectionStartTime(UUID playerId) {
        return this.protectionManager.getTeleportProtectionStartTime(playerId);
    }

    @Override
    public void shutdown() {
        this.requestManager.shutdown();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) {
                this.userSettingsManager.saveUserSettingsToPdc(p);
            }
        }
        if (config().economyEnabled()) {
            String timing = config().getNormalizedChargeTiming();
            if ("CHARGE_ON_SEND".equals(timing) || "CHARGE_ON_ACCEPT".equals(timing)) {
                for (ActiveWarmup warmup : this.warmupManager.activeWarmups().values()) {
                    if (warmup.cost() > 0.0 && warmup.senderId() != null) {
                        this.repository.addPendingRefund(warmup.senderId(), warmup.cost());
                    }
                }
            }
            if ("CHARGE_ON_SEND".equals(timing)) {
                this.repository.forEachRequest(req -> {
                    if (req.cost() > 0.0 && req.senderId() != null) {
                        this.repository.addPendingRefund(req.senderId(), req.cost());
                    }
                });
            }
        }
        this.warmupManager.clear();
        this.protectionManager.clear();
        this.escrowManager.clear();
        this.messenger.clear();
        if (this.economyService != null) {
            this.economyService.shutdown();
        }
        this.repository.clear();
    }
}
