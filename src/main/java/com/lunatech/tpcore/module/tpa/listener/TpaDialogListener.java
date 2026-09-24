package com.lunatech.tpcore.module.tpa.listener;

import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
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
            String keyString = null;
            if (DialogListenerReflectionCache.GET_IDENTIFIER != null) {
                Object keyObj = DialogListenerReflectionCache.GET_IDENTIFIER.invoke(event);
                if (keyObj != null) {
                    keyString = (keyObj instanceof Key adventureKey) ? adventureKey.asString() : keyObj.toString();
                }
            } else if (DialogListenerReflectionCache.GET_KEY != null) {
                Object keyObj = DialogListenerReflectionCache.GET_KEY.invoke(event);
                if (keyObj != null) {
                    keyString = (keyObj instanceof Key adventureKey) ? adventureKey.asString() : keyObj.toString();
                }
            }

            if (keyString == null || !keyString.startsWith("tpcore:tpa_")) {
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

            if (keyString.startsWith("tpcore:tpa_accept/")) {
                String senderIdStr = keyString.substring("tpcore:tpa_accept/".length());
                service.acceptRequest(player, senderIdStr);
            } else if (keyString.startsWith("tpcore:tpa_deny/")) {
                String senderIdStr = keyString.substring("tpcore:tpa_deny/".length());
                service.denyRequest(player, senderIdStr);
            } else if ("tpcore:tpa_accept".equals(keyString)) {
                service.acceptRequest(player, null);
            } else if ("tpcore:tpa_deny".equals(keyString)) {
                service.denyRequest(player, null);
            } else if ("tpcore:tpa_send_cancel".equals(keyString)) {
                closePlayerDialog(player);
            } else if (keyString.startsWith("tpcore:tpa_send_confirm/")) {
                String sub = keyString.substring("tpcore:tpa_send_confirm/".length());
                int slashIdx = sub.indexOf('/');
                if (slashIdx > 0 && slashIdx < sub.length() - 1) {
                    try {
                        String targetIdStr = sub.substring(0, slashIdx);
                        String typeStr = sub.substring(slashIdx + 1);
                        UUID targetId = UUID.fromString(targetIdStr);
                        TpaType type = "tpa_here".equalsIgnoreCase(typeStr) ? TpaType.TPA_HERE : TpaType.TPA_TO;
                        Player target = Bukkit.getPlayer(targetId);
                        if (target != null && target.isOnline()) {
                            service.sendRequest(player, target, type);
                        }
                    } catch (Exception e) {
                        this.logger.warn("Failed to parse send confirmation data from dialog key: {}", keyString);
                    }
                }
            }
        } catch (Exception e) {
            this.logger.error("Error handling Paper Dialog custom click event", e);
        }
    }

    private void closePlayerDialog(Player player) {
        try {
            if (DialogListenerReflectionCache.CLOSE_DIALOG != null) {
                DialogListenerReflectionCache.CLOSE_DIALOG.invoke(player);
                return;
            }
        } catch (Exception ignored) {
        }
        player.closeInventory();
    }

    private Player resolvePlayerFromEvent(Event event) {
        try {
            if (DialogListenerReflectionCache.GET_PLAYER_FROM_EVENT != null) {
                Object playerObj = DialogListenerReflectionCache.GET_PLAYER_FROM_EVENT.invoke(event);
                if (playerObj instanceof Player p) {
                    return p;
                }
            }

            if (DialogListenerReflectionCache.GET_COMMON_CONNECTION != null) {
                Object connection = DialogListenerReflectionCache.GET_COMMON_CONNECTION.invoke(event);
                if (connection != null) {
                    if (connection instanceof Player player) {
                        return player;
                    }

                    if (DialogListenerReflectionCache.GET_PLAYER != null) {
                        Object playerObj = DialogListenerReflectionCache.GET_PLAYER.invoke(connection);
                        if (playerObj instanceof Player p) {
                            return p;
                        }
                    }

                    if (DialogListenerReflectionCache.GET_PROFILE != null && DialogListenerReflectionCache.GET_ID != null) {
                        Object profile = DialogListenerReflectionCache.GET_PROFILE.invoke(connection);
                        if (profile != null) {
                            Object uuidObj = DialogListenerReflectionCache.GET_ID.invoke(profile);
                            if (uuidObj instanceof UUID uuid) {
                                return Bukkit.getPlayer(uuid);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            this.logger.error("Error resolving player from custom click event", e);
        }
        return null;
    }

    private static final class DialogListenerReflectionCache {
        private static final Method GET_PLAYER_FROM_EVENT;
        private static final Method GET_IDENTIFIER;
        private static final Method GET_KEY;
        private static final Method GET_COMMON_CONNECTION;
        private static final Method GET_PLAYER;
        private static final Method GET_PROFILE;
        private static final Method GET_ID;
        private static final Method CLOSE_DIALOG;

        static {
            Method getPlayerFromEv = null;
            Method getIdent = null;
            Method getKey = null;
            Method getConn = null;
            Method getPlayer = null;
            Method getProf = null;
            Method getId = null;
            Method closeDiag = null;

            try {
                Class<?> eventClass = Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent");
                getPlayerFromEv = findMethod(eventClass, "getPlayer");
                if (getPlayerFromEv == null) {
                    getPlayerFromEv = findMethod(PlayerEvent.class, "getPlayer");
                }

                getIdent = findMethod(eventClass, "getIdentifier");
                getKey = findMethod(eventClass, "getKey");
                getConn = findMethod(eventClass, "getCommonConnection");

                Class<?> connClass = Class.forName("io.papermc.paper.network.PlayerCommonConnection");
                getPlayer = findMethod(connClass, "getPlayer");
                getProf = findMethod(connClass, "getProfile");

                try {
                    Class<?> profClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
                    getId = findMethod(profClass, "getId");
                } catch (ClassNotFoundException ignored) {
                    try {
                        Class<?> profClass = Class.forName("org.bukkit.profile.PlayerProfile");
                        getId = findMethod(profClass, "getId");
                    } catch (ClassNotFoundException ignored2) {
                    }
                }

                closeDiag = findMethod(Player.class, "closeDialog");
            } catch (Throwable ignored) {
            }

            GET_PLAYER_FROM_EVENT = getPlayerFromEv;
            GET_IDENTIFIER = getIdent;
            GET_KEY = getKey;
            GET_COMMON_CONNECTION = getConn;
            GET_PLAYER = getPlayer;
            GET_PROFILE = getProf;
            GET_ID = getId;
            CLOSE_DIALOG = closeDiag;
        }

        private static Method findMethod(Class<?> clazz, String name) {
            if (clazz == null) {
                return null;
            }
            try {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(name)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
