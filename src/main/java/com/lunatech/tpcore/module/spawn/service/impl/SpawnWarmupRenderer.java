package com.lunatech.tpcore.module.spawn.service.impl;

import com.lunatech.tpcore.config.model.SpawnConfig;
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SpawnWarmupRenderer {

    private static final Title.Times WARMUP_TITLE_TIMES = Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(200));
    private final MiniMessage miniMessage;
    private final Map<String, Key> soundKeyCache = new ConcurrentHashMap<>();

    public SpawnWarmupRenderer(MiniMessage miniMessage) {
        this.miniMessage = miniMessage;
    }

    public BossBar createBossBar(SpawnConfig cfg, int warmupSeconds) {
        if (!cfg.enableBossbar()) {
            return null;
        }
        BossBar.Color color = this.parseBossBarColor(cfg.bossbarColor());
        BossBar.Overlay overlay = this.parseBossBarOverlay(cfg.bossbarOverlay());
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(warmupSeconds));
        return BossBar.bossBar(
            this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.bossbarFormat()), TagResolver.resolver(prefixResolver, secResolver)),
            1.0f,
            color,
            overlay
        );
    }

    public void updateBossBar(BossBar bossBar, SpawnConfig cfg, int remainingSeconds, int totalWarmupSeconds) {
        if (bossBar == null) {
            return;
        }
        float progress = Math.max(0.0f, Math.min(1.0f, (float) remainingSeconds / (float) totalWarmupSeconds));
        bossBar.progress(progress);
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(remainingSeconds));
        bossBar.name(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.bossbarFormat()), TagResolver.resolver(prefixResolver, secResolver)));
    }

    public void updateWarmupFeedback(Player player, SpawnConfig cfg, int remainingSeconds, int totalWarmupSeconds) {
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(remainingSeconds));
        TagResolver combined = TagResolver.resolver(prefixResolver, secResolver);

        if (cfg.enableActionBar()) {
            player.sendActionBar(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.actionBarFormat()), combined));
        }

        if (cfg.enableTitle()) {
            Title title = Title.title(
                this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.titleFormat()), combined),
                this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.subtitleFormat()), combined),
                WARMUP_TITLE_TIMES
            );
            player.showTitle(title);
        }

        if (cfg.enableSounds()) {
            int elapsed = totalWarmupSeconds - remainingSeconds;
            float progress = (totalWarmupSeconds > 0) ? (float) elapsed / (float) totalWarmupSeconds : 1.0f;
            float rawPitch = 1.0f + progress;
            this.playSound(player, cfg.tickSound(), (float) cfg.tickSoundVolume(), rawPitch);
        }
    }

    public void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null || !player.isOnline() || soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            Key soundKey = this.soundKeyCache.computeIfAbsent(soundName.toLowerCase(Locale.ROOT).trim(), Key::key);
            Sound sound = Sound.sound(soundKey, Sound.Source.PLAYER, volume, pitch);
            player.playSound(sound);
        } catch (Throwable ignored) {}
    }

    private BossBar.Color parseBossBarColor(String colorStr) {
        if (colorStr == null || colorStr.isBlank()) {
            return BossBar.Color.YELLOW;
        }
        try {
            return BossBar.Color.valueOf(colorStr.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Overlay parseBossBarOverlay(String overlayStr) {
        if (overlayStr == null || overlayStr.isBlank()) {
            return BossBar.Overlay.PROGRESS;
        }
        try {
            return BossBar.Overlay.valueOf(overlayStr.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    public void clear() {
        this.soundKeyCache.clear();
    }
}
