package com.lunatech.tpcore.module.home.listener;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import com.lunatech.tpcore.util.MessageFormatter;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

public final class HomeTravelListener implements Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Plugin plugin;
    private final Supplier<DefaultHomeService> service;
    private final Supplier<HomeConfig> settings;

    public HomeTravelListener(Plugin plugin, Supplier<DefaultHomeService> service, Supplier<HomeConfig> settings) {
        this.plugin = plugin; this.service = service; this.settings = settings;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        DefaultHomeService current = service.get();
        if (current == null || !settings.get().cancelOnMove() || !current.hasPending(event.getPlayer().getUniqueId())) return;
        var to = event.getTo();
        if (to == null) return;
        var from = event.getFrom();
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()
            || from.getWorld() != to.getWorld()) current.cancel(event.getPlayer().getUniqueId(), HomeResultStatus.MOVED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        DefaultHomeService current = service.get();
        if (current != null) current.cancel(event.getPlayer().getUniqueId(), HomeResultStatus.CANCELLED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        DefaultHomeService current = service.get();
        if (current != null && settings.get().cancelOnDamage() && event.getFinalDamage() > 0 && event.getEntity() instanceof Player player)
            current.cancel(player.getUniqueId(), HomeResultStatus.DAMAGED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        DefaultHomeService current = service.get();
        if (current != null) current.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        DefaultHomeService current = service.get();
        if (current == null) return;
        Player player = event.getPlayer();
        current.cancel(player.getUniqueId(), HomeResultStatus.CANCELLED);
        HomeConfig config = settings.get();
        if (!config.respawnAtPrimaryHome() || event.isBedSpawn() || event.isAnchorSpawn()
            || event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.END_PORTAL) return;
        // Let the server and Spawn choose the initial location. Only a successful, fully checked
        // post-respawn teleport replaces that destination.
        current.teleport(player, player.getUniqueId(), config.defaultHomeName(), null, true).thenAccept(status -> {
            if (status == HomeResultStatus.SUCCESS && service.get() == current) {
                player.getScheduler().run(plugin, task -> {
                    if (service.get() == current) {
                        var messages = config.messages();
                        String template = messages.respawnAtHome();
                        player.sendMessage(MessageFormatter.parse(MINI, template.contains("<prefix>") ? template : messages.prefix() + template,
                            Placeholder.parsed("prefix", messages.prefix())));
                    }
                }, null);
            }
        });
    }
}
