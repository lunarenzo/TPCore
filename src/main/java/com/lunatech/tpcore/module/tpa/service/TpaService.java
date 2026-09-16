package com.lunatech.tpcore.module.tpa.service;

import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.bukkit.entity.Player;

import java.util.UUID;

public interface TpaService {

    void sendRequest(Player sender, Player target, TpaType type);

    void acceptRequest(Player target, String optionalSenderName);

    void denyRequest(Player target, String optionalSenderName);

    void cancelRequest(Player sender, String optionalTargetName);

    boolean toggleTpa(Player player);

    void handlePlayerQuit(UUID playerId);

    void handlePlayerDamage(UUID playerId);

    void handlePlayerMove(Player player);

    void shutdown();
}
