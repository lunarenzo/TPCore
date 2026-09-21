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
            Method getIdentifierMethod = findPublicMethod(event.getClass(), "getIdentifier");
            if (getIdentifierMethod == null) {
                getIdentifierMethod = findPublicMethod(event.getClass(), "getKey");
            }
            if (getIdentifierMethod == null) {
                this.logger.warn("Could not find getIdentifier or getKey on PlayerCustomClickEvent: {}", event.getClass().getName());
                return;
            }
            getIdentifierMethod.setAccessible(true);

            Object keyObj = getIdentifierMethod.invoke(event);
            if (keyObj == null) {
                return;
            }

            String keyString = (keyObj instanceof Key adventureKey) ? adventureKey.asString() : keyObj.toString();
            if (!keyString.startsWith("tpcore:tpa_")) {
                return;
            }

            Player player = resolvePlayerFromEvent(event);
            if (player == null || !player.isOnline()) {
                this.logger.warn("Could not resolve online player for custom click event key: {}", keyString);
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
            Method getCommonConnectionMethod = findPublicMethod(event.getClass(), "getCommonConnection");
            if (getCommonConnectionMethod == null) {
                return null;
            }
            getCommonConnectionMethod.setAccessible(true);
            Object connection = getCommonConnectionMethod.invoke(event);
            if (connection == null) {
                return null;
            }

            if (connection instanceof Player player) {
                return player;
            }

            Method getPlayerMethod = findPublicMethod(connection.getClass(), "getPlayer");
            if (getPlayerMethod != null) {
                getPlayerMethod.setAccessible(true);
                Object playerObj = getPlayerMethod.invoke(connection);
                if (playerObj instanceof Player p) {
                    return p;
                }
            }

            Method getProfileMethod = findPublicMethod(connection.getClass(), "getProfile");
            if (getProfileMethod != null) {
                getProfileMethod.setAccessible(true);
                Object profile = getProfileMethod.invoke(connection);
                if (profile != null) {
                    Method getIdMethod = findPublicMethod(profile.getClass(), "getId");
                    if (getIdMethod != null) {
                        getIdMethod.setAccessible(true);
                        Object uuidObj = getIdMethod.invoke(profile);
                        if (uuidObj instanceof UUID uuid) {
                            return Bukkit.getPlayer(uuid);
                        }
                    }
                }
            }
        } catch (Exception e) {
            this.logger.error("Error resolving player from custom click event", e);
        }
        return null;
    }

    private Method findPublicMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            for (Class<?> itf : c.getInterfaces()) {
                for (Method m : itf.getDeclaredMethods()) {
                    if (m.getName().equals(name)) {
                        return m;
                    }
                }
            }
        }
        return null;
    }
}
