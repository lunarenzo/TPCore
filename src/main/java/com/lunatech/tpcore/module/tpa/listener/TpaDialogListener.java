package com.lunatech.tpcore.module.tpa.listener;

import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Supplier;

public final class TpaDialogListener implements Listener {

    private final JavaPlugin plugin;
    private final Supplier<TpaService> serviceSupplier;
    private final Logger logger;

    public TpaDialogListener(JavaPlugin plugin, Supplier<TpaService> serviceSupplier) {
        this.plugin = plugin;
        this.serviceSupplier = serviceSupplier;
        this.logger = plugin.getSLF4JLogger();
    }

    @SuppressWarnings("unchecked")
    public void registerIfSupported() {
        try {
            Class<?> eventClass = Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent");
            if (Event.class.isAssignableFrom(eventClass)) {
                PluginManager pm = this.plugin.getServer().getPluginManager();
                EventExecutor executor = (listener, event) -> handleCustomClick(event);
                pm.registerEvent(
                    (Class<? extends Event>) eventClass,
                    this,
                    EventPriority.HIGH,
                    executor,
                    this.plugin
                );
                this.logger.info("Registered Paper PlayerCustomClickEvent listener for TPA Dialog confirmation menus.");
            }
        } catch (ClassNotFoundException ignored) {
            // Paper Dialog API not available on Paper < 1.21.7
        } catch (Exception e) {
            this.logger.error("Failed to register Paper PlayerCustomClickEvent listener", e);
        }
    }

    private void handleCustomClick(Event event) {
        try {
            Method getIdentifierMethod = event.getClass().getMethod("getIdentifier");
            Object keyObj = getIdentifierMethod.invoke(event);
            if (keyObj == null) {
                return;
            }

            String keyString;
            if (keyObj instanceof Key adventureKey) {
                keyString = adventureKey.asString();
            } else {
                keyString = keyObj.toString();
            }

            if (!keyString.startsWith("tpcore:tpa_")) {
                return;
            }

            Player player = resolvePlayerFromEvent(event);
            if (player == null || !player.isOnline()) {
                return;
            }

            TpaService service = this.serviceSupplier.get();
            if (service == null) {
                return;
            }

            if ("tpcore:tpa_accept".equals(keyString)) {
                service.acceptRequest(player, null);
            } else if ("tpcore:tpa_deny".equals(keyString)) {
                service.denyRequest(player, null);
            } else if (keyString.startsWith("tpcore:tpa_send_confirm:")) {
                String[] parts = keyString.split(":");
                if (parts.length >= 4) {
                    try {
                        UUID targetId = UUID.fromString(parts[2]);
                        TpaType type = TpaType.valueOf(parts[3]);
                        Player target = Bukkit.getPlayer(targetId);
                        if (target != null && target.isOnline()) {
                            service.sendRequest(player, target, type);
                        }
                    } catch (Exception e) {
                        this.logger.error("Failed to parse send confirmation data from dialog key: {}", keyString, e);
                    }
                }
            }
        } catch (Exception e) {
            this.logger.error("Error handling Paper Dialog custom click event", e);
        }
    }

    private Player resolvePlayerFromEvent(Event event) {
        try {
            Method getCommonConnectionMethod = event.getClass().getMethod("getCommonConnection");
            Object connection = getCommonConnectionMethod.invoke(event);
            if (connection == null) {
                return null;
            }

            if (connection instanceof Player player) {
                return player;
            }

            try {
                Method getPlayerMethod = connection.getClass().getMethod("getPlayer");
                Object playerObj = getPlayerMethod.invoke(connection);
                if (playerObj instanceof Player p) {
                    return p;
                }
            } catch (NoSuchMethodException ignored) {
            }

            try {
                Method getProfileMethod = connection.getClass().getMethod("getProfile");
                Object profile = getProfileMethod.invoke(connection);
                if (profile != null) {
                    Method getIdMethod = profile.getClass().getMethod("getId");
                    Object uuidObj = getIdMethod.invoke(profile);
                    if (uuidObj instanceof UUID uuid) {
                        return Bukkit.getPlayer(uuid);
                    }
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
