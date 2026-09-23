package com.lunatech.tpcore.module.tpa.service;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface TpaService {

    void sendRequest(Player sender, Player target, TpaType type);

    void sendBulkRequests(Player sender, List<Player> targets, TpaType type);

    void acceptRequest(Player target, String optionalSenderName);

    void denyRequest(Player target, String optionalSenderName);

    void cancelRequest(Player sender, String optionalTargetName);

    boolean toggleTpa(Player player);

    boolean toggleAutoAccept(Player player);

    void blockPlayer(Player player, String targetName);

    void unblockPlayer(Player player, String targetName);

    void listBlockedPlayers(Player player);

    Set<UUID> getBlockedPlayers(Player player);

    Collection<TpaRequest> getPendingRequestsForTarget(Player target);

    Collection<TpaRequest> getOutgoingRequestsForSender(Player sender);

    TpaRequest findPendingRequest(Player target, String optionalSenderName);

    void handlePlayerJoin(Player player);

    void handlePlayerQuit(UUID playerId);

    void handlePlayerDamage(UUID playerId);

    void handlePlayerMove(Player player);

    void handlePlayerTeleport(UUID playerId);

    void handlePlayerDeath(UUID playerId);

    void updateConfig(TpaConfig newConfig);

    void shutdown();
}
