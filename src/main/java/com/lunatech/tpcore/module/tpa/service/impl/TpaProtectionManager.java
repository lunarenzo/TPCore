package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Manages post-teleport damage invulnerability window and combat cancellation.
 */
final class TpaProtectionManager {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaMessenger messenger;
    private final Map<UUID, Long> teleportProtectionMap = new ConcurrentHashMap<>();

    TpaProtectionManager(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaMessenger messenger) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.messenger = messenger;
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    void grantTeleportProtection(Player player) {
        if (player == null || !player.isOnline() || config().protectionSeconds() <= 0) {
            return;
        }
        int seconds = config().protectionSeconds();
        long expiry = System.currentTimeMillis() + (seconds * 1000L);
        this.teleportProtectionMap.put(player.getUniqueId(), expiry);
        this.messenger.sendMessage(
            player,
            config().messages().teleportProtectionStart(),
            "seconds", String.valueOf(seconds)
        );

        player.getScheduler().runDelayed(
            this.plugin,
            task -> {
                if (player.isOnline()) {
                    Long exp = this.teleportProtectionMap.get(player.getUniqueId());
                    if (exp != null && System.currentTimeMillis() >= exp) {
                        if (this.teleportProtectionMap.remove(player.getUniqueId(), exp)) {
                            this.messenger.sendMessage(player, config().messages().teleportProtectionEnded());
                        }
                    }
                }
            },
            null,
            seconds * 20L
        );
    }

    boolean hasTeleportProtection(UUID playerId) {
        if (playerId == null || this.teleportProtectionMap.isEmpty()) {
            return false;
        }
        Long expiry = this.teleportProtectionMap.get(playerId);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry) {
            if (this.teleportProtectionMap.remove(playerId, expiry)) {
                Player p = Bukkit.getPlayer(playerId);
                if (p != null && p.isOnline()) {
                    this.messenger.sendMessage(p, config().messages().teleportProtectionEnded());
                }
            }
            return false;
        }
        return true;
    }

    long getTeleportProtectionStartTime(UUID playerId) {
        if (playerId == null || this.teleportProtectionMap.isEmpty()) {
            return 0L;
        }
        Long expiry = this.teleportProtectionMap.get(playerId);
        if (expiry == null || System.currentTimeMillis() >= expiry) {
            return 0L;
        }
        int seconds = config().protectionSeconds();
        return expiry - (seconds * 1000L);
    }

    void stripTeleportProtection(UUID playerId) {
        if (playerId == null || this.teleportProtectionMap.isEmpty()) {
            return;
        }
        Long removed = this.teleportProtectionMap.remove(playerId);
        if (removed != null) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                this.messenger.sendMessage(p, config().messages().teleportProtectionEnded());
            }
        }
    }

    boolean handlePlayerProtectionDamage(Player victim, Player attacker, boolean isPvp) {
        if (attacker != null && config().protectionCancelOnAttack() && hasTeleportProtection(attacker.getUniqueId())) {
            stripTeleportProtection(attacker.getUniqueId());
        }

        if (victim != null) {
            if (!isPvp && !config().protectionAllDamage()) {
                return false;
            }
            return hasTeleportProtection(victim.getUniqueId());
        }
        return false;
    }

    void evict(UUID playerId) {
        this.teleportProtectionMap.remove(playerId);
    }

    void clear() {
        this.teleportProtectionMap.clear();
    }
}
