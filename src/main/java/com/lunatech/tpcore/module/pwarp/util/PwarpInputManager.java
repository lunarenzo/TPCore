package com.lunatech.tpcore.module.pwarp.util;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PwarpInputManager implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, Consumer<String>> pendingInputs = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PwarpInputManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
    }

    public void requestInput(Player player, String promptMessage, Consumer<String> callback) {
        Objects.requireNonNull(player, "player cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");

        this.pendingInputs.put(player.getUniqueId(), callback);

        if (promptMessage != null && !promptMessage.isBlank()) {
            player.sendMessage(this.miniMessage.deserialize(promptMessage));
            player.sendMessage(this.miniMessage.deserialize("<gray>Type your response in chat, or type <red>cancel</red> to abort.</gray>"));
        }
    }

    public void cancelInput(UUID playerUuid) {
        if (playerUuid != null) {
            this.pendingInputs.remove(playerUuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPaperAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> callback = this.pendingInputs.remove(player.getUniqueId());
        if (callback == null) {
            return;
        }

        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(this.miniMessage.deserialize("<red>Input cancelled.</red>"));
            return;
        }

        player.getScheduler().run(this.plugin, task -> callback.accept(text), null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpigotAsyncChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> callback = this.pendingInputs.remove(player.getUniqueId());
        if (callback == null) {
            return;
        }

        event.setCancelled(true);
        String text = event.getMessage().trim();

        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(this.miniMessage.deserialize("<red>Input cancelled.</red>"));
            return;
        }

        player.getScheduler().run(this.plugin, task -> callback.accept(text), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelInput(event.getPlayer().getUniqueId());
    }
}
