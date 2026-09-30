package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;

/**
 * Handles UI rendering (BossBars, Titles, ActionBars, and Sounds) during teleport warmups.
 */
final class TpaWarmupRenderer {

    private static final Title.Times WARMUP_TITLE_TIMES = Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(200));
    private final TpaMessenger messenger;

    TpaWarmupRenderer(TpaMessenger messenger) {
        this.messenger = messenger;
    }

    BossBar createBossBar(TpaConfig cfg, int warmupSeconds) {
        if (!cfg.enableBossbar()) {
            return null;
        }
        BossBar.Color color = parseBossBarColor(cfg.bossbarColor());
        BossBar.Overlay overlay = parseBossBarOverlay(cfg.bossbarOverlay());
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(warmupSeconds));
        return BossBar.bossBar(
            this.messenger.miniMessage().deserialize(MessageFormatter.toMiniMessage(cfg.bossbarFormat()), TagResolver.resolver(prefixResolver, secResolver)),
            1.0f,
            color,
            overlay
        );
    }

    void updateBossBar(BossBar bossBar, TpaConfig cfg, int remainingSeconds, int totalWarmupSeconds) {
        if (bossBar == null) {
            return;
        }
        float progress = Math.max(0.0f, Math.min(1.0f, (float) remainingSeconds / (float) totalWarmupSeconds));
        bossBar.progress(progress);
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(remainingSeconds));
        bossBar.name(this.messenger.miniMessage().deserialize(MessageFormatter.toMiniMessage(cfg.bossbarFormat()), TagResolver.resolver(prefixResolver, secResolver)));
    }

    void updateWarmupFeedback(Player player, TpaConfig cfg, int remainingSeconds, int totalWarmupSeconds) {
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(remainingSeconds));
        TagResolver combined = TagResolver.resolver(prefixResolver, secResolver);

        if (cfg.enableActionBar()) {
            player.sendActionBar(this.messenger.miniMessage().deserialize(MessageFormatter.toMiniMessage(cfg.actionBarFormat()), combined));
        }

        if (cfg.enableTitle()) {
            Title title = Title.title(
                this.messenger.miniMessage().deserialize(MessageFormatter.toMiniMessage(cfg.titleFormat()), combined),
                this.messenger.miniMessage().deserialize(MessageFormatter.toMiniMessage(cfg.subtitleFormat()), combined),
                WARMUP_TITLE_TIMES
            );
            player.showTitle(title);
        }

        if (cfg.enableSounds()) {
            int elapsed = totalWarmupSeconds - remainingSeconds;
            float progress = (totalWarmupSeconds > 0) ? (float) elapsed / (float) totalWarmupSeconds : 1.0f;
            float rawPitch = 1.0f + progress;
            this.messenger.playSound(player, cfg.tickSound(), (float) cfg.tickSoundVolume(), rawPitch);
        }
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
}
