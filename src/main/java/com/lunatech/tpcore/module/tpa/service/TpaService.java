package com.lunatech.tpcore.module.tpa.service;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

public interface TpaService {

    void sendRequest(Player sender, Player target, TpaType type);

    void acceptRequest(Player target, String optionalSenderName);

    void denyRequest(Player target, String optionalSenderName);

    void cancelRequest(Player sender, String optionalTargetName);

    boolean toggleTpa(Player player);

    Collection<TpaRequest> getPendingRequestsForTarget(Player target);

    void handlePlayerQuit(UUID playerId);

    void handlePlayerDamage(UUID playerId);

    void handlePlayerMove(Player player);

    void updateConfig(TpaConfig newConfig);

    void shutdown();
}
