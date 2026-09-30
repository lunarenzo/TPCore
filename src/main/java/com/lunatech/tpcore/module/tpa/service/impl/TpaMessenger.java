package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Message and audio feedback dispatcher for TPA module operations.
 */
final class TpaMessenger {

    private final Supplier<TpaConfig> configSupplier;
    private final MiniMessage miniMessage;
    private final Map<String, Key> soundKeyCache = new ConcurrentHashMap<>();

    TpaMessenger(Supplier<TpaConfig> configSupplier) {
        this.configSupplier = configSupplier;
        this.miniMessage = MiniMessage.miniMessage();
    }

    TpaConfig config() {
        return this.configSupplier.get();
    }

    MiniMessage miniMessage() {
        return this.miniMessage;
    }

    void sendMessage(Player player, String template) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config().messages().prefix()));
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), prefixResolver));
    }

    void sendMessage(Player player, String template, String key, String value) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safeValue = value != null ? value : "";
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config().messages().prefix()));
        TagResolver valueResolver = Placeholder.unparsed(key, safeValue);
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), TagResolver.resolver(prefixResolver, valueResolver)));
    }

    void sendMessage(Player player, String template, String key1, String value1, String key2, String value2) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safe1 = value1 != null ? value1 : "";
        String safe2 = value2 != null ? value2 : "";
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config().messages().prefix()));
        player.sendMessage(this.miniMessage.deserialize(
            MessageFormatter.toMiniMessage(template),
            TagResolver.resolver(
                prefixResolver,
                Placeholder.unparsed(key1, safe1),
                Placeholder.unparsed(key2, safe2)
            )
        ));
    }

    void sendMessage(Player player, String template, TagResolver... customResolvers) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config().messages().prefix()));
        TagResolver combined;
        if (customResolvers == null || customResolvers.length == 0) {
            combined = prefixResolver;
        } else {
            TagResolver[] all = new TagResolver[customResolvers.length + 1];
            all[0] = prefixResolver;
            System.arraycopy(customResolvers, 0, all, 1, customResolvers.length);
            combined = TagResolver.resolver(all);
        }
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
    }

    void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null || !player.isOnline() || soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            Key soundKey = this.soundKeyCache.computeIfAbsent(soundName.toLowerCase().trim(), Key::key);
            Sound sound = Sound.sound(soundKey, Sound.Source.PLAYER, volume, pitch);
            player.playSound(sound);
        } catch (Throwable ignored) {
        }
    }

    void clear() {
        this.soundKeyCache.clear();
    }
}
