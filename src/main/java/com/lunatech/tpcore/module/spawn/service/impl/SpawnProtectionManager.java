package com.lunatech.tpcore.module.spawn.service.impl;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class SpawnProtectionManager {

    private final JavaPlugin plugin;
    private final Supplier<SpawnConfig> configSupplier;
    private final MiniMessage miniMessage;

    private record ProtectionWindow(long startTime, long expiryTime) {}

    private final Map<UUID, ProtectionWindow> protectionMap = new ConcurrentHashMap<>();

    public SpawnProtectionManager(JavaPlugin plugin, Supplier<SpawnConfig> configSupplier, MiniMessage miniMessage) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.miniMessage = miniMessage;
    }

    private SpawnConfig config() {
        return this.configSupplier.get();
    }

    public void grantTeleportProtection(Player player) {
        if (player == null || !player.isOnline() || config().protectionSeconds() <= 0) {
            return;
        }
        int seconds = config().protectionSeconds();
        long now = System.currentTimeMillis();
        long expiry = now + (seconds * 1000L);
        ProtectionWindow window = new ProtectionWindow(now, expiry);
        this.protectionMap.put(player.getUniqueId(), window);

        this.sendMessage(
            player,
            config().messages().teleportProtectionStart(),
            Placeholder.unparsed("seconds", String.valueOf(seconds))
        );

        player.getScheduler().runDelayed(
            this.plugin,
            task -> {
                if (player.isOnline()) {
                    ProtectionWindow current = this.protectionMap.get(player.getUniqueId());
                    if (current != null && System.currentTimeMillis() >= current.expiryTime()) {
                        if (this.protectionMap.remove(player.getUniqueId(), current)) {
                            this.sendMessage(player, config().messages().teleportProtectionEnded());
                        }
                    }
                }
            },
            null,
            seconds * 20L
        );
    }

    public boolean hasTeleportProtection(UUID playerId) {
        if (playerId == null || this.protectionMap.isEmpty()) {
            return false;
        }
        ProtectionWindow window = this.protectionMap.get(playerId);
        if (window == null) {
            return false;
        }
        if (System.currentTimeMillis() >= window.expiryTime()) {
            if (this.protectionMap.remove(playerId, window)) {
                Player p = Bukkit.getPlayer(playerId);
                if (p != null && p.isOnline()) {
                    this.sendMessage(p, config().messages().teleportProtectionEnded());
                }
            }
            return false;
        }
        return true;
    }

    public long getTeleportProtectionStartTime(UUID playerId) {
        if (playerId == null || this.protectionMap.isEmpty()) {
            return 0L;
        }
        ProtectionWindow window = this.protectionMap.get(playerId);
        if (window == null || System.currentTimeMillis() >= window.expiryTime()) {
            return 0L;
        }
        return window.startTime();
    }

    public void stripTeleportProtection(UUID playerId) {
        if (playerId == null || this.protectionMap.isEmpty()) {
            return;
        }
        ProtectionWindow removed = this.protectionMap.remove(playerId);
        if (removed != null) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                this.sendMessage(p, config().messages().teleportProtectionEnded());
            }
        }
    }

    public boolean handlePlayerProtectionDamage(Player victim, Player attacker, boolean isPvp) {
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

    private void sendMessage(Player player, String template, TagResolver... resolvers) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(this.config().messages().prefix()));
        TagResolver combined = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
    }

    public void evict(UUID playerId) {
        this.protectionMap.remove(playerId);
    }

    public void clear() {
        this.protectionMap.clear();
    }
}
