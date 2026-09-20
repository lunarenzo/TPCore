package com.lunatech.tpcore.module.pwarp.economy;

import org.bukkit.OfflinePlayer;

/**
 * Service boundary interface for economy integrations (Vault / VaultUnlocked).
 */
public interface PwarpEconomyService {

    boolean isAvailable();

    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);

    String format(double amount);
}
