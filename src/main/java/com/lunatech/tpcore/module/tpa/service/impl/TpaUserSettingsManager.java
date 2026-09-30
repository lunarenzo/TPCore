package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaUserSettings;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages player settings persistence via PersistentDataContainer and privacy block lists.
 */
final class TpaUserSettingsManager {

    private final JavaPlugin plugin;
    private final Supplier<TpaConfig> configSupplier;
    private final TpaRepository repository;
    private final TpaMessenger messenger;
    private final TpaEscrowManager escrowManager;
    private final TpaWarmupManager warmupManager;

    private final NamespacedKey keyToggledOff;
    private final NamespacedKey keyAutoAccept;
    private final NamespacedKey keyBlockList;

    TpaUserSettingsManager(JavaPlugin plugin, Supplier<TpaConfig> configSupplier, TpaRepository repository,
                           TpaMessenger messenger, TpaEscrowManager escrowManager, TpaWarmupManager warmupManager) {
        this.plugin = plugin;
        this.configSupplier = configSupplier;
        this.repository = repository;
        this.messenger = messenger;
        this.escrowManager = escrowManager;
        this.warmupManager = warmupManager;

        this.keyToggledOff = (plugin != null) ? new NamespacedKey(plugin, "tpa_toggled_off") : null;
        this.keyAutoAccept = (plugin != null) ? new NamespacedKey(plugin, "tpa_auto_accept") : null;
        this.keyBlockList = (plugin != null) ? new NamespacedKey(plugin, "tpa_block_list") : null;
    }

    private TpaConfig config() {
        return this.configSupplier.get();
    }

    void loadUserSettingsFromPdc(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte toggledOffByte = (this.keyToggledOff != null) ? pdc.get(this.keyToggledOff, PersistentDataType.BYTE) : null;
        boolean toggledOff = toggledOffByte != null && toggledOffByte == (byte) 1;

        Byte autoAcceptByte = (this.keyAutoAccept != null) ? pdc.get(this.keyAutoAccept, PersistentDataType.BYTE) : null;
        boolean autoAccept = autoAcceptByte != null && autoAcceptByte == (byte) 1;

        Set<UUID> blockedSet = Collections.emptySet();
        if (this.keyBlockList != null) {
            if (pdc.has(this.keyBlockList, PersistentDataType.BYTE_ARRAY)) {
                byte[] bytes = pdc.get(this.keyBlockList, PersistentDataType.BYTE_ARRAY);
                blockedSet = bytesToUuidSet(bytes);
            } else if (pdc.has(this.keyBlockList, PersistentDataType.STRING)) {
                String blockedStr = pdc.get(this.keyBlockList, PersistentDataType.STRING);
                if (blockedStr != null && !blockedStr.isBlank()) {
                    blockedSet = new HashSet<>();
                    int start = 0;
                    int len = blockedStr.length();
                    while (start < len) {
                        int comma = blockedStr.indexOf(',', start);
                        int end = (comma == -1) ? len : comma;
                        String part = blockedStr.substring(start, end).trim();
                        if (!part.isEmpty()) {
                            try {
                                blockedSet.add(UUID.fromString(part));
                            } catch (Exception ignored) {
                            }
                        }
                        if (comma == -1) {
                            break;
                        }
                        start = comma + 1;
                    }
                    blockedSet = Collections.unmodifiableSet(blockedSet);
                }
                pdc.remove(this.keyBlockList);
                byte[] bytes = uuidSetToBytes(blockedSet);
                if (bytes.length > 0) {
                    pdc.set(this.keyBlockList, PersistentDataType.BYTE_ARRAY, bytes);
                }
            }
        }

        TpaUserSettings settings = new TpaUserSettings(toggledOff, autoAccept, blockedSet);
        this.repository.setUserSettings(player.getUniqueId(), settings);
    }

    void saveUserSettingsToPdc(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        TpaUserSettings settings = this.repository.getUserSettings(player.getUniqueId());
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (this.keyToggledOff != null) {
            pdc.set(this.keyToggledOff, PersistentDataType.BYTE, settings.toggledOff() ? (byte) 1 : (byte) 0);
        }
        if (this.keyAutoAccept != null) {
            pdc.set(this.keyAutoAccept, PersistentDataType.BYTE, settings.autoAccept() ? (byte) 1 : (byte) 0);
        }

        if (this.keyBlockList != null) {
            if (settings.blockedPlayers() != null && !settings.blockedPlayers().isEmpty()) {
                byte[] bytes = uuidSetToBytes(settings.blockedPlayers());
                pdc.set(this.keyBlockList, PersistentDataType.BYTE_ARRAY, bytes);
            } else {
                pdc.remove(this.keyBlockList);
            }
        }
    }

    boolean toggleTpa(Player player) {
        boolean currentlyOff = this.repository.isTpaToggledOff(player.getUniqueId());
        boolean newStatus = !currentlyOff;
        this.repository.setTpaToggledOff(player.getUniqueId(), newStatus);
        saveUserSettingsToPdc(player);

        if (newStatus) {
            Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(player.getUniqueId());
            if (outgoing != null && !outgoing.isEmpty()) {
                this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                for (TpaRequest req : outgoing) {
                    this.escrowManager.refundRequestSenderIfCharged(req, "Sender toggled TPA off");
                    Player target = Bukkit.getPlayer(req.targetId());
                    if (target != null && target.isOnline()) {
                        final UUID senderId = player.getUniqueId();
                        target.getScheduler().run(
                            this.plugin,
                            t -> TpaMenuCloser.closeConfirmationMenuIfOpen(target, senderId),
                            null
                        );
                    }
                }
            }
            Collection<TpaRequest> incoming = this.repository.getIncomingRequests(player.getUniqueId());
            if (incoming != null && !incoming.isEmpty()) {
                for (TpaRequest req : incoming) {
                    this.escrowManager.refundRequestSenderIfCharged(req, "Target toggled TPA off");
                    this.repository.setCooldownEnd(req.senderId(), 0L);
                    Player sender = Bukkit.getPlayer(req.senderId());
                    if (sender != null && sender.isOnline()) {
                        sender.getScheduler().run(
                            this.plugin,
                            t -> {
                                TpaMenuCloser.closeConfirmationMenuIfOpen(sender, player.getUniqueId());
                                this.messenger.sendMessage(sender, config().messages().targetToggledOff(), "target", player.getName());
                            },
                            null
                        );
                    }
                }
            }
            this.repository.removeAllRequestsForPlayer(player.getUniqueId());
            this.warmupManager.cancelWarmup(player.getUniqueId(), null);
            this.warmupManager.cancelWarmupsForDestination(player.getUniqueId(), config().messages().targetToggledOff());
            this.messenger.sendMessage(player, config().messages().toggleOff());
        } else {
            this.messenger.sendMessage(player, config().messages().toggleOn());
        }
        return !newStatus;
    }

    boolean toggleAutoAccept(Player player, Consumer<Player> autoAcceptTrigger) {
        boolean current = this.repository.isAutoAcceptEnabled(player.getUniqueId());
        boolean newStatus = !current;
        this.repository.setAutoAcceptEnabled(player.getUniqueId(), newStatus);
        saveUserSettingsToPdc(player);

        if (newStatus) {
            this.messenger.sendMessage(player, config().messages().autoAcceptOn());
            if (!this.repository.getIncomingRequests(player.getUniqueId()).isEmpty()
                    && !this.warmupManager.isPlayerInWarmup(player.getUniqueId())) {
                if (autoAcceptTrigger != null) {
                    autoAcceptTrigger.accept(player);
                }
            }
            if (this.warmupManager.isPlayerInWarmup(player.getUniqueId())) {
                Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(player.getUniqueId());
                if (outgoing != null && !outgoing.isEmpty()) {
                    for (TpaRequest req : outgoing) {
                        this.escrowManager.refundRequestSenderIfCharged(req, "Sender in warmup");
                    }
                }
                Collection<TpaRequest> incoming = this.repository.getIncomingRequests(player.getUniqueId());
                if (incoming != null && !incoming.isEmpty()) {
                    for (TpaRequest req : incoming) {
                        this.escrowManager.refundRequestSenderIfCharged(req, "Target in warmup");
                        this.repository.setCooldownEnd(req.senderId(), 0L);
                    }
                }
                this.repository.removeAllRequestsForPlayer(player.getUniqueId());
            }
        } else {
            this.messenger.sendMessage(player, config().messages().autoAcceptOff());
        }
        return newStatus;
    }

    void blockPlayer(Player player, String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            target = Bukkit.getPlayer(targetName);
        }
        OfflinePlayer offlineTarget = (target == null) ? TpaPlayerResolver.resolveOfflinePlayerIfCached(targetName) : null;
        UUID targetId = (target != null) ? target.getUniqueId() : (offlineTarget != null ? offlineTarget.getUniqueId() : null);

        if (targetId == null) {
            String trimmed = targetName.trim();
            try {
                targetId = UUID.fromString(trimmed);
            } catch (Throwable ignored) {
            }
        }

        if (targetId == null) {
            this.messenger.sendMessage(player, config().messages().playerNotOnline(), "player", targetName);
            return;
        }

        if (offlineTarget == null && target == null) {
            offlineTarget = TpaPlayerResolver.resolveOfflinePlayerIfCached(targetId);
        }

        if (player.getUniqueId().equals(targetId)) {
            this.messenger.sendMessage(player, config().messages().rejectSelfTpa());
            return;
        }

        Optional<TpaRequest> incomingReq = this.repository.getRequest(player.getUniqueId(), targetId);
        incomingReq.ifPresent(req -> {
            this.escrowManager.refundRequestSenderIfCharged(req, "Sender blocked by target");
            this.repository.setCooldownEnd(req.senderId(), 0L);
        });

        Optional<TpaRequest> outgoingReq = this.repository.getRequest(targetId, player.getUniqueId());
        outgoingReq.ifPresent(req -> {
            this.escrowManager.refundRequestSenderIfCharged(req, "Target blocked by sender");
            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
        });

        this.repository.setPlayerBlocked(player.getUniqueId(), targetId, true);
        this.repository.removeRequest(player.getUniqueId(), targetId);
        this.repository.removeRequest(targetId, player.getUniqueId());
        this.warmupManager.cancelWarmup(player.getUniqueId(), null);
        this.warmupManager.cancelWarmup(targetId, null);
        TpaMenuCloser.closeConfirmationMenuIfOpen(player, targetId);
        if (target != null && target.isOnline()) {
            final Player finalTarget = target;
            target.getScheduler().run(
                this.plugin,
                t -> TpaMenuCloser.closeConfirmationMenuIfOpen(finalTarget, player.getUniqueId()),
                null
            );
        }
        saveUserSettingsToPdc(player);

        String displayName = (target != null) ? target.getName() : ((offlineTarget != null && offlineTarget.getName() != null) ? offlineTarget.getName() : targetName);
        this.messenger.sendMessage(player, config().messages().playerBlocked(), "player", displayName);
    }

    void unblockPlayer(Player player, String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            target = Bukkit.getPlayer(targetName);
        }
        OfflinePlayer offlineTarget = (target == null) ? TpaPlayerResolver.resolveOfflinePlayerIfCached(targetName) : null;
        UUID targetId = (target != null) ? target.getUniqueId() : (offlineTarget != null ? offlineTarget.getUniqueId() : null);

        if (targetId == null) {
            String trimmed = targetName.trim();
            try {
                UUID parsedUuid = UUID.fromString(trimmed);
                if (this.repository.isPlayerBlocked(player.getUniqueId(), parsedUuid)) {
                    targetId = parsedUuid;
                }
            } catch (Throwable ignored) {
            }
        }

        if (targetId == null) {
            String trimmed = targetName.trim();
            Set<UUID> blocked = getBlockedPlayers(player);
            for (UUID bId : blocked) {
                String bStr = bId.toString();
                if (bStr.equalsIgnoreCase(trimmed) || bStr.startsWith(trimmed)) {
                    targetId = bId;
                    break;
                }
            }
        }

        if (targetId == null || !this.repository.isPlayerBlocked(player.getUniqueId(), targetId)) {
            this.messenger.sendMessage(player, config().messages().notBlocked(), "player", targetName);
            return;
        }

        if (offlineTarget == null && target == null) {
            offlineTarget = TpaPlayerResolver.resolveOfflinePlayerIfCached(targetId);
        }

        this.repository.setPlayerBlocked(player.getUniqueId(), targetId, false);
        saveUserSettingsToPdc(player);

        String displayName = (target != null) ? target.getName() : ((offlineTarget != null && offlineTarget.getName() != null) ? offlineTarget.getName() : targetName);
        this.messenger.sendMessage(player, config().messages().playerUnblocked(), "player", displayName);
    }

    void listBlockedPlayers(Player player) {
        if (player == null) {
            return;
        }
        TpaUserSettings settings = this.repository.getUserSettings(player.getUniqueId());
        if (settings.blockedPlayers() == null || settings.blockedPlayers().isEmpty()) {
            this.messenger.sendMessage(player, config().messages().blockListEmpty());
            return;
        }

        List<String> names = new ArrayList<>();
        for (UUID uuid : settings.blockedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                names.add(p.getName());
            } else {
                OfflinePlayer op = TpaPlayerResolver.resolveOfflinePlayerIfCached(uuid);
                String name = (op != null) ? op.getName() : null;
                names.add(name != null ? name : uuid.toString().substring(0, 8) + "...");
            }
        }
        String joined = String.join(", ", names);
        this.messenger.sendMessage(player, config().messages().blockListHeader(), "players", joined);
    }

    Set<UUID> getBlockedPlayers(Player player) {
        if (player == null) {
            return Collections.emptySet();
        }
        TpaUserSettings settings = this.repository.getUserSettings(player.getUniqueId());
        return (settings.blockedPlayers() != null) ? settings.blockedPlayers() : Collections.emptySet();
    }

    private static byte[] uuidSetToBytes(Set<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return new byte[0];
        }
        byte[] bytes = new byte[uuids.size() * 16];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        return bytes;
    }

    private static Set<UUID> bytesToUuidSet(byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            return Collections.emptySet();
        }
        int count = bytes.length / 16;
        int initialCapacity = (count * 4 + 2) / 3;
        Set<UUID> set = new HashSet<>(initialCapacity);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < count; i++) {
            long most = buffer.getLong();
            long least = buffer.getLong();
            set.add(new UUID(most, least));
        }
        return Collections.unmodifiableSet(set);
    }
}
