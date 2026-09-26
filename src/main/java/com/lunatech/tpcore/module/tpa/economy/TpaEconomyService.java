package com.lunatech.tpcore.module.tpa.economy;

import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Service boundary interface for TPA module economy operations.
 */
public interface TpaEconomyService {

    boolean isAvailable();

    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);

    double getBalance(OfflinePlayer player);

    String format(double amount);

    double getCost(Player player, TpaType type);

    boolean processSendCost(Player player, TpaType type);

    void processRefund(OfflinePlayer player, double amount, String reason);

    void processReward(Player target, Player sender, double cost);
}
