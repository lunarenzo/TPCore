package com.lunatech.tpcore.module.home.listener;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.cache.HomeCache;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class HomeJoinQuitListener implements Listener {

    private final HomeRepository repository;
    private final HomeCache cache;
    private final Supplier<HomeConfig> configSupplier;

    public HomeJoinQuitListener(HomeRepository repository, HomeCache cache, Supplier<HomeConfig> configSupplier) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!configSupplier.get().enabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        repository.loadAll(uuid).thenAccept(homes -> {
            if (Bukkit.getPlayer(uuid) != null && configSupplier.get().enabled()) {
                cache.loadPlayer(uuid, homes);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cache.unloadPlayer(uuid);
    }
}
