package com.lunatech.tpcore.module.home.listener;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.service.HomeService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class HomeRespawnListener implements Listener {

    private final HomeService homeService;
    private final Supplier<HomeConfig> configSupplier;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public HomeRespawnListener(HomeService homeService, Supplier<HomeConfig> configSupplier) {
        this.homeService = Objects.requireNonNull(homeService, "homeService cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        HomeConfig config = configSupplier.get();
        if (!config.enabled() || !config.respawnAtPrimaryHome()) {
            return;
        }

        if (event.isBedSpawn() || event.isAnchorSpawn() || event.getRespawnReason() == PlayerRespawnEvent.RespawnReason.END_PORTAL) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String defaultName = config.defaultHomeName() != null ? config.defaultHomeName() : "home";

        Optional<Home> optHome = homeService.getHome(uuid, defaultName);
        if (optHome.isEmpty()) {
            return;
        }

        Home home = optHome.get();
        World world = Bukkit.getWorld(home.worldName());
        if (world == null) {
            return;
        }

        Location target = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        if (homeService.isLocationSafe(target)) {
            event.setRespawnLocation(target);
            String msg = config.messages().prefix() + config.messages().respawnAtHome();
            player.sendMessage(miniMessage.deserialize(msg));
        }
    }
}
