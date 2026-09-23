package com.lunatech.tpcore.module.tpa.service.impl;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.tpa.gui.TpaConfirmationHolder;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import com.lunatech.tpcore.module.tpa.model.TpaUserSettings;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;
import com.lunatech.tpcore.module.tpa.service.TpaService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultTpaService implements TpaService {

    private final JavaPlugin plugin;
    private final TpaRepository repository;
    private final AtomicReference<TpaConfig> configRef;
    private final MiniMessage miniMessage;
    private final NamespacedKey keyToggledOff;
    private final NamespacedKey keyAutoAccept;
    private final NamespacedKey keyBlockList;

    private final Map<UUID, ActiveWarmup> activeWarmups = new ConcurrentHashMap<>();
    private final Map<String, Key> soundKeyCache = new ConcurrentHashMap<>();
    private final ScheduledTask sweeperTask;

    private record ActiveWarmup(
        UUID teleportingPlayerId,
        UUID destinationPlayerId,
        String worldName,
        double startX,
        double startY,
        double startZ,
        int totalWarmupSeconds,
        AtomicInteger remainingSeconds,
        AtomicBoolean cancelled,
        BossBar bossBar,
        AtomicReference<ScheduledTask> taskRef
    ) {
        public boolean hasMoved(Location currentLoc) {
            if (currentLoc == null || currentLoc.getWorld() == null) {
                return true;
            }
            if (!this.worldName.equals(currentLoc.getWorld().getName())) {
                return true;
            }
            double dx = currentLoc.getX() - this.startX;
            double dy = currentLoc.getY() - this.startY;
            double dz = currentLoc.getZ() - this.startZ;
            return (dx * dx + dy * dy + dz * dz) > 0.25;
        }
    }

    public DefaultTpaService(JavaPlugin plugin, TpaRepository repository, TpaConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.configRef = new AtomicReference<>(config);
        this.miniMessage = MiniMessage.miniMessage();
        this.keyToggledOff = new NamespacedKey(plugin, "tpa_toggled_off");
        this.keyAutoAccept = new NamespacedKey(plugin, "tpa_auto_accept");
        this.keyBlockList = new NamespacedKey(plugin, "tpa_block_list");
        this.sweeperTask = this.startExpirationSweeper();
    }

    private TpaConfig config() {
        return this.configRef.get();
    }

    @Override
    public void updateConfig(TpaConfig newConfig) {
        if (newConfig != null) {
            this.configRef.set(newConfig);
        }
    }

    private ScheduledTask startExpirationSweeper() {
        return this.plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
            this.plugin,
            task -> {
                List<TpaRequest> expired = new ArrayList<>();
                this.repository.forEachRequest(request -> {
                    if (request.isExpired(this.config().requestTimeoutSeconds())) {
                        expired.add(request);
                    }
                });

                this.repository.clearExpiredCooldowns();

                if (!expired.isEmpty()) {
                    for (TpaRequest request : expired) {
                        this.repository.removeRequest(request.targetId(), request.senderId());

                        Player sender = Bukkit.getPlayer(request.senderId());
                        if (sender != null && sender.isOnline()) {
                            Player target = Bukkit.getPlayer(request.targetId());
                            String targetName = (target != null && target.isOnline()) ? target.getName() : null;
                            if (targetName == null) {
                                OfflinePlayer op = Bukkit.getOfflinePlayer(request.targetId());
                                targetName = (op.hasPlayedBefore() || op.isOnline()) ? op.getName() : null;
                            }
                            String finalTargetName = (targetName != null) ? targetName : "Player";
                            sender.getScheduler().run(
                                this.plugin,
                                t -> {
                                    this.closeConfirmationMenuIfOpen(sender, request.targetId());
                                    this.sendMessage(
                                        sender,
                                        this.config().messages().requestExpired(),
                                        "player", finalTargetName
                                    );
                                },
                                null
                            );
                        }

                        Player target = Bukkit.getPlayer(request.targetId());
                        if (target != null && target.isOnline()) {
                            Player senderPlayer = Bukkit.getPlayer(request.senderId());
                            String senderName = (senderPlayer != null && senderPlayer.isOnline()) ? senderPlayer.getName() : null;
                            if (senderName == null) {
                                OfflinePlayer op = Bukkit.getOfflinePlayer(request.senderId());
                                senderName = (op.hasPlayedBefore() || op.isOnline()) ? op.getName() : null;
                            }
                            String finalSenderName = (senderName != null) ? senderName : "Player";
                            target.getScheduler().run(
                                this.plugin,
                                t -> {
                                    this.closeConfirmationMenuIfOpen(target, request.senderId());
                                    this.sendMessage(
                                        target,
                                        this.config().messages().requestExpired(),
                                        "player", finalSenderName
                                    );
                                },
                                null
                            );
                        }
                    }
                }
            },
            100L,
            100L
        );
    }

    @Override
    public void handlePlayerJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Byte toggledOffByte = pdc.get(this.keyToggledOff, PersistentDataType.BYTE);
        boolean toggledOff = toggledOffByte != null && toggledOffByte == (byte) 1;

        Byte autoAcceptByte = pdc.get(this.keyAutoAccept, PersistentDataType.BYTE);
        boolean autoAccept = autoAcceptByte != null && autoAcceptByte == (byte) 1;

        Set<UUID> blockedSet = Collections.emptySet();
        if (pdc.has(this.keyBlockList, PersistentDataType.BYTE_ARRAY)) {
            byte[] bytes = pdc.get(this.keyBlockList, PersistentDataType.BYTE_ARRAY);
            blockedSet = bytesToUuidSet(bytes);
        } else if (pdc.has(this.keyBlockList, PersistentDataType.STRING)) {
            String blockedStr = pdc.get(this.keyBlockList, PersistentDataType.STRING);
            if (blockedStr != null && !blockedStr.isBlank()) {
                blockedSet = new HashSet<>();
                for (String raw : blockedStr.split(",")) {
                    try {
                        if (!raw.isBlank()) {
                            blockedSet.add(UUID.fromString(raw.trim()));
                        }
                    } catch (Exception ignored) {
                    }
                }
                blockedSet = Collections.unmodifiableSet(blockedSet);
            }
            pdc.remove(this.keyBlockList);
            byte[] bytes = uuidSetToBytes(blockedSet);
            if (bytes.length > 0) {
                pdc.set(this.keyBlockList, PersistentDataType.BYTE_ARRAY, bytes);
            }
        }

        TpaUserSettings settings = new TpaUserSettings(toggledOff, autoAccept, blockedSet);
        this.repository.setUserSettings(player.getUniqueId(), settings);
    }

    private void saveUserSettingsToPdc(Player player) {
        if (player == null) {
            return;
        }
        TpaUserSettings settings = this.repository.getUserSettings(player.getUniqueId());
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(this.keyToggledOff, PersistentDataType.BYTE, settings.toggledOff() ? (byte) 1 : (byte) 0);
        pdc.set(this.keyAutoAccept, PersistentDataType.BYTE, settings.autoAccept() ? (byte) 1 : (byte) 0);

        if (settings.blockedPlayers() != null && !settings.blockedPlayers().isEmpty()) {
            byte[] bytes = uuidSetToBytes(settings.blockedPlayers());
            pdc.set(this.keyBlockList, PersistentDataType.BYTE_ARRAY, bytes);
        } else {
            pdc.remove(this.keyBlockList);
        }
    }

    private static byte[] uuidSetToBytes(Set<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return new byte[0];
        }
        byte[] bytes = new byte[uuids.size() * 16];
        int idx = 0;
        for (UUID uuid : uuids) {
            long most = uuid.getMostSignificantBits();
            long least = uuid.getLeastSignificantBits();
            for (int i = 7; i >= 0; i--) {
                bytes[idx++] = (byte) (most >>> (i * 8));
            }
            for (int i = 7; i >= 0; i--) {
                bytes[idx++] = (byte) (least >>> (i * 8));
            }
        }
        return bytes;
    }

    private static Set<UUID> bytesToUuidSet(byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            return Collections.emptySet();
        }
        int count = bytes.length / 16;
        int initialCapacity = (int) Math.ceil(count / 0.75f);
        Set<UUID> set = new HashSet<>(initialCapacity);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < count; i++) {
            long most = buffer.getLong();
            long least = buffer.getLong();
            set.add(new UUID(most, least));
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    public void sendRequest(Player sender, Player target, TpaType type) {
        processSingleSendRequest(sender, target, type, true);
    }

    @Override
    public void sendBulkRequests(Player sender, List<Player> targets, TpaType type) {
        if (sender == null || targets == null || targets.isEmpty()) {
            return;
        }

        int cooldownSeconds = this.config().requestCooldownSeconds();
        if (cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            long cooldownEnd = this.repository.getCooldownEnd(sender.getUniqueId());
            long now = System.currentTimeMillis();
            if (cooldownEnd > now) {
                long remSeconds = (cooldownEnd - now + 999L) / 1000L;
                this.sendMessage(
                    sender,
                    this.config().messages().cooldownActive(),
                    "seconds", String.valueOf(remSeconds)
                );
                return;
            }
        }

        boolean sentAny = false;
        for (Player target : targets) {
            if (target != null && target.isOnline()) {
                if (processSingleSendRequest(sender, target, type, false)) {
                    sentAny = true;
                }
            }
        }

        if (sentAny && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            this.repository.setCooldownEnd(sender.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        }
    }

    private boolean processSingleSendRequest(Player sender, Player target, TpaType type, boolean applyCooldown) {
        if (!this.config().allowSelfTpa() && sender.getUniqueId().equals(target.getUniqueId())) {
            this.sendMessage(sender, this.config().messages().rejectSelfTpa());
            return false;
        }

        if (this.repository.isTpaToggledOff(target.getUniqueId()) || this.repository.isPlayerBlocked(target.getUniqueId(), sender.getUniqueId())) {
            this.sendMessage(
                sender,
                this.config().messages().targetToggledOff(),
                "target", target.getName()
            );
            return false;
        }

        if (this.repository.isAutoAcceptEnabled(target.getUniqueId())) {
            TpaRequest req = new TpaRequest(sender.getUniqueId(), target.getUniqueId(), type, System.currentTimeMillis());
            this.repository.addRequest(req);
            this.sendMessage(sender, this.config().messages().requestAutoAcceptedSender(), "target", target.getName());
            this.sendMessage(target, this.config().messages().requestAutoAcceptedTarget(), "sender", sender.getName());
            this.acceptRequest(target, sender.getName());
            return true;
        }

        int cooldownSeconds = this.config().requestCooldownSeconds();
        if (applyCooldown && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            long cooldownEnd = this.repository.getCooldownEnd(sender.getUniqueId());
            long now = System.currentTimeMillis();
            if (cooldownEnd > now) {
                long remSeconds = (cooldownEnd - now + 999L) / 1000L;
                this.sendMessage(
                    sender,
                    this.config().messages().cooldownActive(),
                    "seconds", String.valueOf(remSeconds)
                );
                return false;
            }
        }

        Optional<TpaRequest> existing = this.repository.getRequest(target.getUniqueId(), sender.getUniqueId());
        if (existing.isPresent() && !existing.get().isExpired(this.config().requestTimeoutSeconds())) {
            this.sendMessage(
                sender,
                this.config().messages().alreadyHasPendingRequest(),
                "target", target.getName()
            );
            return false;
        }

        int maxRequests = this.config().maxPendingRequestsPerPlayer();
        if (maxRequests > 0) {
            Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
            long activeCount = incoming.stream()
                .filter(req -> !req.isExpired(this.config().requestTimeoutSeconds()))
                .count();
            if (activeCount >= maxRequests) {
                this.sendMessage(
                    sender,
                    this.config().messages().maxPendingRequestsReached(),
                    "target", target.getName()
                );
                return false;
            }
        }

        TpaRequest request = new TpaRequest(
            sender.getUniqueId(),
            target.getUniqueId(),
            type,
            System.currentTimeMillis()
        );

        this.repository.addRequest(request);

        if (applyCooldown && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            this.repository.setCooldownEnd(sender.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        }

        if (type == TpaType.TPA_TO) {
            this.sendMessage(
                sender,
                this.config().messages().senderTpaSent(),
                "target", target.getName(),
                "seconds", String.valueOf(this.config().requestTimeoutSeconds())
            );

            this.sendMessage(
                target,
                this.config().messages().targetTpaReceived(),
                "sender", sender.getName()
            );
        } else {
            this.sendMessage(
                sender,
                this.config().messages().senderTpaHereSent(),
                "target", target.getName(),
                "seconds", String.valueOf(this.config().requestTimeoutSeconds())
            );

            this.sendMessage(
                target,
                this.config().messages().targetTpaHereReceived(),
                "sender", sender.getName()
            );
        }
        return true;
    }

    @Override
    public void acceptRequest(Player target, String optionalSenderName) {
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());

        if (incoming.isEmpty()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;

        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            targetRequest = resolveMatchingRequest(incoming, optionalSenderName);
        } else if (incoming.size() == 1) {
            targetRequest = incoming.iterator().next();
        } else {
            this.sendMessage(target, this.config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest == null || targetRequest.isExpired(this.config().requestTimeoutSeconds())) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            if (targetRequest != null) {
                this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());
            }
            return;
        }

        if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        Player sender = Bukkit.getPlayer(targetRequest.senderId());
        if (sender == null || !sender.isOnline()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        this.sendMessage(
            target,
            this.config().messages().requestAcceptedTarget(),
            "sender", sender.getName()
        );

        this.sendMessage(
            sender,
            this.config().messages().requestAcceptedSender(),
            "target", target.getName()
        );

        Player teleportingPlayer = (targetRequest.type() == TpaType.TPA_TO) ? sender : target;
        Player destinationPlayer = (targetRequest.type() == TpaType.TPA_TO) ? target : sender;

        this.executeTeleportSequence(teleportingPlayer, destinationPlayer);
    }

    @Override
    public void denyRequest(Player target, String optionalSenderName) {
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
        if (incoming.isEmpty()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            targetRequest = resolveMatchingRequest(incoming, optionalSenderName);
        } else if (incoming.size() == 1) {
            targetRequest = incoming.iterator().next();
        } else {
            this.sendMessage(target, this.config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest != null) {
            if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
                this.sendMessage(target, this.config().messages().noPendingRequests());
                return;
            }
            Player sender = Bukkit.getPlayer(targetRequest.senderId());
            if (sender != null && sender.isOnline()) {
                this.closeConfirmationMenuIfOpen(sender, targetRequest.targetId());
                this.sendMessage(
                    sender,
                    this.config().messages().requestDeniedSender(),
                    "target", target.getName()
                );
                if (this.config().enableSounds()) {
                    TpaConfig cfg = this.config();
                    playSound(sender, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                }
            }
            this.closeConfirmationMenuIfOpen(target, targetRequest.senderId());
            this.sendMessage(
                target,
                this.config().messages().requestDeniedTarget(),
                "sender", (sender != null) ? sender.getName() : "Player"
            );
            if (this.config().enableSounds()) {
                TpaConfig cfg = this.config();
                playSound(target, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
            }
        } else {
            this.sendMessage(target, this.config().messages().noPendingRequests());
        }
    }

    @Override
    public void cancelRequest(Player sender, String optionalTargetName) {
        Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(sender.getUniqueId());
        if (outgoing.isEmpty()) {
            this.sendMessage(sender, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalTargetName != null && !optionalTargetName.isBlank()) {
            targetRequest = resolveMatchingOutgoingRequest(outgoing, optionalTargetName);
        } else if (outgoing.size() == 1) {
            targetRequest = outgoing.iterator().next();
        } else {
            this.sendMessage(sender, this.config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest != null) {
            if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
                this.sendMessage(sender, this.config().messages().noPendingRequests());
                return;
            }
            Player target = Bukkit.getPlayer(targetRequest.targetId());
            if (target != null && target.isOnline()) {
                this.closeConfirmationMenuIfOpen(target, targetRequest.senderId());
                this.sendMessage(
                    target,
                    this.config().messages().requestCancelledTarget(),
                    "sender", sender.getName()
                );
                if (this.config().enableSounds()) {
                    TpaConfig cfg = this.config();
                    playSound(target, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                }
            }
            this.closeConfirmationMenuIfOpen(sender, targetRequest.targetId());
            this.sendMessage(
                sender,
                this.config().messages().requestCancelledSender(),
                "target", (target != null) ? target.getName() : "Player"
            );
            if (this.config().enableSounds()) {
                TpaConfig cfg = this.config();
                playSound(sender, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
            }
        } else {
            this.sendMessage(sender, this.config().messages().noPendingRequests());
        }
    }

    @Override
    public boolean toggleTpa(Player player) {
        boolean currentlyOff = this.repository.isTpaToggledOff(player.getUniqueId());
        boolean newStatus = !currentlyOff;
        this.repository.setTpaToggledOff(player.getUniqueId(), newStatus);
        this.saveUserSettingsToPdc(player);

        if (newStatus) {
            this.sendMessage(player, this.config().messages().toggleOff());
        } else {
            this.sendMessage(player, this.config().messages().toggleOn());
        }
        return !newStatus;
    }

    @Override
    public boolean toggleAutoAccept(Player player) {
        boolean current = this.repository.isAutoAcceptEnabled(player.getUniqueId());
        boolean newStatus = !current;
        this.repository.setAutoAcceptEnabled(player.getUniqueId(), newStatus);
        this.saveUserSettingsToPdc(player);

        if (newStatus) {
            this.sendMessage(player, this.config().messages().autoAcceptOn());
        } else {
            this.sendMessage(player, this.config().messages().autoAcceptOff());
        }
        return newStatus;
    }

    @Override
    public void blockPlayer(Player player, String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        OfflinePlayer offlineTarget = (target == null) ? Bukkit.getOfflinePlayer(targetName) : null;

        if (target == null && (offlineTarget == null || (!offlineTarget.hasPlayedBefore() && !offlineTarget.isOnline()))) {
            this.sendMessage(player, this.config().messages().playerNotOnline(), "player", targetName);
            return;
        }
        UUID targetId = (target != null) ? target.getUniqueId() : offlineTarget.getUniqueId();

        if (player.getUniqueId().equals(targetId)) {
            this.sendMessage(player, this.config().messages().rejectSelfTpa());
            return;
        }

        this.repository.setPlayerBlocked(player.getUniqueId(), targetId, true);
        this.saveUserSettingsToPdc(player);

        String displayName = (target != null) ? target.getName() : ((offlineTarget != null && offlineTarget.getName() != null) ? offlineTarget.getName() : targetName);
        this.sendMessage(player, this.config().messages().playerBlocked(), "player", displayName);
    }

    @Override
    public void unblockPlayer(Player player, String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        OfflinePlayer offlineTarget = (target == null) ? Bukkit.getOfflinePlayer(targetName) : null;
        UUID targetId = (target != null) ? target.getUniqueId() : (offlineTarget != null ? offlineTarget.getUniqueId() : null);

        if (targetId == null || !this.repository.isPlayerBlocked(player.getUniqueId(), targetId)) {
            this.sendMessage(player, this.config().messages().notBlocked(), "player", targetName);
            return;
        }

        this.repository.setPlayerBlocked(player.getUniqueId(), targetId, false);
        this.saveUserSettingsToPdc(player);

        String displayName = (target != null) ? target.getName() : ((offlineTarget != null && offlineTarget.getName() != null) ? offlineTarget.getName() : targetName);
        this.sendMessage(player, this.config().messages().playerUnblocked(), "player", displayName);
    }

    @Override
    public void listBlockedPlayers(Player player) {
        if (player == null) {
            return;
        }
        TpaUserSettings settings = this.repository.getUserSettings(player.getUniqueId());
        if (settings.blockedPlayers() == null || settings.blockedPlayers().isEmpty()) {
            this.sendMessage(player, this.config().messages().blockListEmpty());
            return;
        }

        List<String> names = new ArrayList<>();
        for (UUID uuid : settings.blockedPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                names.add(p.getName());
            } else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                String name = (op.hasPlayedBefore() || op.isOnline()) ? op.getName() : null;
                names.add(name != null ? name : uuid.toString().substring(0, 8));
            }
        }
        String joined = String.join(", ", names);
        this.sendMessage(player, this.config().messages().blockListHeader(), "players", joined);
    }

    @Override
    public Set<UUID> getBlockedPlayers(Player player) {
        if (player == null) {
            return Collections.emptySet();
        }
        TpaUserSettings settings = this.repository.getUserSettings(player.getUniqueId());
        return (settings.blockedPlayers() != null) ? settings.blockedPlayers() : Collections.emptySet();
    }

    @Override
    public Collection<TpaRequest> getPendingRequestsForTarget(Player target) {
        if (target == null) {
            return Collections.emptyList();
        }
        return this.repository.getIncomingRequests(target.getUniqueId());
    }

    @Override
    public Collection<TpaRequest> getOutgoingRequestsForSender(Player sender) {
        if (sender == null) {
            return Collections.emptyList();
        }
        return this.repository.getOutgoingRequests(sender.getUniqueId());
    }

    @Override
    public TpaRequest findPendingRequest(Player target, String optionalSenderName) {
        if (target == null) {
            return null;
        }
        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
        if (incoming.isEmpty()) {
            return null;
        }

        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            TpaRequest matched = resolveMatchingRequest(incoming, optionalSenderName);
            if (matched != null && !matched.isExpired(this.config().requestTimeoutSeconds())) {
                return matched;
            }
            return null;
        }

        if (incoming.size() == 1) {
            TpaRequest first = incoming.iterator().next();
            if (!first.isExpired(this.config().requestTimeoutSeconds())) {
                return first;
            }
        }

        return null;
    }

    private TpaRequest resolveMatchingRequest(Collection<TpaRequest> incoming, String senderIdentifier) {
        if (incoming == null || incoming.isEmpty() || senderIdentifier == null || senderIdentifier.isBlank()) {
            return null;
        }

        String trimmed = senderIdentifier.trim();
        for (TpaRequest req : incoming) {
            if (req.senderId().toString().equalsIgnoreCase(trimmed)) {
                return req;
            }
            Player sender = Bukkit.getPlayer(req.senderId());
            if (sender != null && sender.getName().equalsIgnoreCase(trimmed)) {
                return req;
            }
            if (sender == null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(req.senderId());
                String cachedName = (op.hasPlayedBefore() || op.isOnline()) ? op.getName() : null;
                if (cachedName != null && cachedName.equalsIgnoreCase(trimmed)) {
                    return req;
                }
            }
        }
        return null;
    }

    private TpaRequest resolveMatchingOutgoingRequest(Collection<TpaRequest> outgoing, String targetIdentifier) {
        if (outgoing == null || outgoing.isEmpty() || targetIdentifier == null || targetIdentifier.isBlank()) {
            return null;
        }

        String trimmed = targetIdentifier.trim();
        for (TpaRequest req : outgoing) {
            if (req.targetId().toString().equalsIgnoreCase(trimmed)) {
                return req;
            }
            Player target = Bukkit.getPlayer(req.targetId());
            if (target != null && target.getName().equalsIgnoreCase(trimmed)) {
                return req;
            }
            if (target == null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(req.targetId());
                String cachedName = (op.hasPlayedBefore() || op.isOnline()) ? op.getName() : null;
                if (cachedName != null && cachedName.equalsIgnoreCase(trimmed)) {
                    return req;
                }
            }
        }
        return null;
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            this.saveUserSettingsToPdc(player);
        }

        Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(playerId);
        if (outgoing != null && !outgoing.isEmpty()) {
            for (TpaRequest req : outgoing) {
                Player target = Bukkit.getPlayer(req.targetId());
                if (target != null && target.isOnline()) {
                    this.closeConfirmationMenuIfOpen(target, playerId);
                    this.sendMessage(
                        target,
                        this.config().messages().requestCancelledTarget(),
                        "sender", (player != null) ? player.getName() : "Player"
                    );
                }
            }
        }

        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(playerId);
        if (incoming != null && !incoming.isEmpty()) {
            for (TpaRequest req : incoming) {
                Player sender = Bukkit.getPlayer(req.senderId());
                if (sender != null && sender.isOnline()) {
                    this.closeConfirmationMenuIfOpen(sender, playerId);
                    this.sendMessage(
                        sender,
                        this.config().messages().requestCancelledSender(),
                        "target", (player != null) ? player.getName() : "Player"
                    );
                }
            }
        }

        this.repository.removeAllRequestsForPlayer(playerId);
        this.cancelWarmup(playerId, null);
        this.cancelWarmupsForDestination(playerId, this.config().messages().targetToggledOff());
    }

    @Override
    public void handlePlayerDamage(UUID playerId) {
        if (this.config().cancelOnDamage()) {
            this.cancelWarmup(playerId, this.config().messages().warmupCancelledDamage());
        }
    }

    @Override
    public void handlePlayerMove(Player player) {
        if (!this.config().cancelOnMove() || this.activeWarmups.isEmpty()) {
            return;
        }
        ActiveWarmup warmup = this.activeWarmups.get(player.getUniqueId());
        if (warmup != null && warmup.hasMoved(player.getLocation())) {
            this.cancelWarmup(player.getUniqueId(), this.config().messages().warmupCancelledMove());
        }
    }

    @Override
    public void handlePlayerTeleport(UUID playerId) {
        this.cancelWarmup(playerId, null);
        this.cancelWarmupsForDestination(playerId, this.config().messages().targetToggledOff());
    }

    @Override
    public void handlePlayerDeath(UUID playerId) {
        this.repository.removeAllRequestsForPlayer(playerId);
        this.cancelWarmup(playerId, null);
        this.cancelWarmupsForDestination(playerId, this.config().messages().targetToggledOff());
    }

    private void cancelWarmupsForDestination(UUID destinationId, String cancelMessage) {
        if (this.activeWarmups.isEmpty() || destinationId == null) {
            return;
        }
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup != null && destinationId.equals(warmup.destinationPlayerId())) {
                this.cancelWarmup(warmup.teleportingPlayerId(), cancelMessage);
            }
        }
    }

    private void executeTeleportSequence(Player player, Player destinationPlayer) {
        if (player == null || !player.isOnline() || destinationPlayer == null || !destinationPlayer.isOnline()) {
            return;
        }

        int warmupSeconds = this.config().warmupSeconds();
        if (warmupSeconds <= 0 || player.hasPermission(Permissions.TPA_BYPASS_WARMUP)) {
            performFinalTeleport(player, destinationPlayer);
            return;
        }

        this.cancelWarmup(player.getUniqueId(), null);

        this.sendMessage(
            player,
            this.config().messages().warmupStart(),
            "seconds", String.valueOf(warmupSeconds)
        );

        TpaConfig cfg = this.config();
        BossBar bossBar = null;
        if (cfg.enableBossbar()) {
            BossBar.Color color = parseBossBarColor(cfg.bossbarColor());
            BossBar.Overlay overlay = parseBossBarOverlay(cfg.bossbarOverlay());
            TagResolver prefixResolver = Placeholder.parsed("prefix", cfg.messages().prefix());
            TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(warmupSeconds));
            bossBar = BossBar.bossBar(
                this.miniMessage.deserialize(cfg.bossbarFormat().replace("<seconds>", String.valueOf(warmupSeconds)), TagResolver.resolver(prefixResolver, secResolver)),
                1.0f,
                color,
                overlay
            );
            player.showBossBar(bossBar);
        }

        Location currentLoc = player.getLocation();
        AtomicInteger remaining = new AtomicInteger(warmupSeconds);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<ScheduledTask> taskRef = new AtomicReference<>();
        BossBar finalBossBar = bossBar;

        ActiveWarmup warmup = new ActiveWarmup(
            player.getUniqueId(),
            destinationPlayer.getUniqueId(),
            currentLoc.getWorld().getName(),
            currentLoc.getX(),
            currentLoc.getY(),
            currentLoc.getZ(),
            warmupSeconds,
            remaining,
            cancelled,
            finalBossBar,
            taskRef
        );
        this.activeWarmups.put(player.getUniqueId(), warmup);

        updateWarmupFeedback(player, warmupSeconds, warmupSeconds);

        ScheduledTask task = player.getScheduler().runAtFixedRate(
            this.plugin,
            scheduledTask -> {
                if (!player.isOnline() || !destinationPlayer.isOnline() || cancelled.get()) {
                    scheduledTask.cancel();
                    String cancelMsg = (!destinationPlayer.isOnline() && player.isOnline()) ? this.config().messages().targetToggledOff() : null;
                    this.cancelWarmup(player.getUniqueId(), cancelMsg);
                    return;
                }

                int rem = remaining.decrementAndGet();
                if (rem > 0) {
                    if (cancelled.get()) {
                        scheduledTask.cancel();
                        return;
                    }
                    updateWarmupFeedback(player, rem, warmupSeconds);
                    if (finalBossBar != null) {
                        float progress = Math.max(0.0f, Math.min(1.0f, (float) rem / (float) warmupSeconds));
                        finalBossBar.progress(progress);
                        TagResolver prefixResolver = Placeholder.parsed("prefix", cfg.messages().prefix());
                        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(rem));
                        finalBossBar.name(this.miniMessage.deserialize(cfg.bossbarFormat(), TagResolver.resolver(prefixResolver, secResolver)));
                    }
                } else {
                    scheduledTask.cancel();
                    ActiveWarmup removed = this.activeWarmups.remove(player.getUniqueId());
                    if (removed != null && !cancelled.getAndSet(true)) {
                        if (finalBossBar != null) {
                            player.hideBossBar(finalBossBar);
                        }
                        if (cfg.enableTitle()) {
                            player.clearTitle();
                        }
                        if (cfg.enableSounds()) {
                            playSound(player, cfg.completionSound(), (float) cfg.completionSoundVolume(), (float) cfg.completionSoundPitch());
                        }
                        performFinalTeleport(player, destinationPlayer);
                    }
                }
            },
            null,
            20L,
            20L
        );

        taskRef.set(task);
    }

    private void updateWarmupFeedback(Player player, int remainingSeconds, int totalWarmupSeconds) {
        TpaConfig cfg = this.config();
        TagResolver prefixResolver = Placeholder.parsed("prefix", cfg.messages().prefix());
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(remainingSeconds));
        TagResolver combined = TagResolver.resolver(prefixResolver, secResolver);

        if (cfg.enableActionBar()) {
            String processed = cfg.actionBarFormat().replace("<seconds>", String.valueOf(remainingSeconds));
            player.sendActionBar(this.miniMessage.deserialize(processed, combined));
        }

        if (cfg.enableTitle()) {
            String processedTitle = cfg.titleFormat().replace("<seconds>", String.valueOf(remainingSeconds));
            String processedSubtitle = cfg.subtitleFormat().replace("<seconds>", String.valueOf(remainingSeconds));
            Title title = Title.title(
                this.miniMessage.deserialize(processedTitle, combined),
                this.miniMessage.deserialize(processedSubtitle, combined),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(200))
            );
            player.showTitle(title);
        }

        if (cfg.enableSounds()) {
            int elapsed = totalWarmupSeconds - remainingSeconds;
            float rawPitch = (float) (1.0 + (cfg.tickSoundPitchStep() * elapsed));
            float pitch = cfg.tickSoundVolume() > 0 ? (float) Math.min(2.0, Math.max(0.5, rawPitch)) : 1.0f;
            playSound(player, cfg.tickSound(), (float) cfg.tickSoundVolume(), pitch);
        }
    }

    private void playSound(Player player, String soundKey, float volume, float pitch) {
        if (player == null || !player.isOnline() || soundKey == null || soundKey.isBlank()) {
            return;
        }
        try {
            Key key = resolveSoundKey(soundKey);
            if (key != null) {
                Sound sound = Sound.sound(key, Sound.Source.MASTER, volume, pitch);
                player.playSound(sound);
            }
        } catch (Throwable ignored) {
        }
    }

    private Key resolveSoundKey(String soundKey) {
        if (soundKey == null || soundKey.isBlank()) {
            return null;
        }
        return this.soundKeyCache.computeIfAbsent(soundKey, rawKey -> {
            String trimmed = rawKey.trim();
            try {
                org.bukkit.Sound bukkitSound = org.bukkit.Sound.valueOf(trimmed.toUpperCase(Locale.ROOT));
                return Key.key(bukkitSound.getKey().getNamespace(), bukkitSound.getKey().getKey());
            } catch (Throwable ignored) {
            }

            String cleanKey = trimmed.toLowerCase(Locale.ROOT);
            if (!cleanKey.contains(":")) {
                cleanKey = "minecraft:" + cleanKey;
            }
            try {
                return Key.key(cleanKey);
            } catch (Throwable ignored) {
                return null;
            }
        });
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

    private void performFinalTeleport(Player player, Player destinationPlayer) {
        if (player == null || !player.isOnline() || destinationPlayer == null || !destinationPlayer.isOnline()) {
            return;
        }

        destinationPlayer.getScheduler().run(
            this.plugin,
            destTask -> {
                if (!player.isOnline() || !destinationPlayer.isOnline()) {
                    return;
                }

                Location rawTargetLoc = destinationPlayer.getLocation();
                Location finalTargetLoc = rawTargetLoc;

                if (this.config().requireSafeLocation()) {
                    Location safeLoc = TpaSafetyInspector.findSafeLocation(rawTargetLoc);
                    if (safeLoc == null) {
                        this.sendMessage(player, this.config().messages().unsafeDestination());
                        this.sendMessage(destinationPlayer, this.config().messages().unsafeDestination());
                        return;
                    }
                    finalTargetLoc = safeLoc;
                }

                Location destination = finalTargetLoc;
                player.getScheduler().run(
                    this.plugin,
                    playerTask -> {
                        if (!player.isOnline() || !destinationPlayer.isOnline() || destination.getWorld() == null) {
                            return;
                        }
                        if (player.isInsideVehicle()) {
                            if (player.getVehicle() != null) {
                                player.getVehicle().removePassenger(player);
                            }
                            player.leaveVehicle();
                        }
                        player.teleportAsync(destination);
                    },
                    null
                );
            },
            null
        );
    }

    private void cancelWarmup(UUID playerId, String cancelMessageTemplate) {
        ActiveWarmup warmup = this.activeWarmups.remove(playerId);
        if (warmup != null) {
            warmup.cancelled().set(true);
            if (warmup.taskRef() != null && warmup.taskRef().get() != null) {
                warmup.taskRef().get().cancel();
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                if (warmup.bossBar() != null) {
                    player.hideBossBar(warmup.bossBar());
                }
                if (this.config().enableTitle()) {
                    player.clearTitle();
                }
                if (cancelMessageTemplate != null) {
                    this.sendMessage(player, cancelMessageTemplate);
                    if (this.config().enableSounds()) {
                        TpaConfig cfg = this.config();
                        playSound(player, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                    }
                }
            }
        }
    }

    private void closeConfirmationMenuIfOpen(Player player) {
        closeConfirmationMenuIfOpen(player, null);
    }

    private void closeConfirmationMenuIfOpen(Player player, UUID expectedOtherPlayerId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null && player.getOpenInventory().getTopInventory().getHolder() instanceof TpaConfirmationHolder holder) {
                boolean matches = (expectedOtherPlayerId == null);
                if (expectedOtherPlayerId != null) {
                    if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.ACCEPT_REQUEST && holder.getRequest() != null) {
                        matches = expectedOtherPlayerId.equals(holder.getRequest().senderId());
                    } else if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.SEND_REQUEST && holder.getTargetPlayer() != null) {
                        matches = expectedOtherPlayerId.equals(holder.getTargetPlayer().getUniqueId());
                    }
                }
                if (matches) {
                    player.closeInventory();
                }
            }
        } catch (Throwable ignored) {
        }
        if (DialogCloseReflectionCache.CLOSE_DIALOG != null) {
            try {
                DialogCloseReflectionCache.CLOSE_DIALOG.invoke(player);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class DialogCloseReflectionCache {
        private static final java.lang.reflect.Method CLOSE_DIALOG;
        static {
            java.lang.reflect.Method m = null;
            try {
                m = Player.class.getMethod("closeDialog");
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }
            CLOSE_DIALOG = m;
        }
    }

    private void sendMessage(Player player, String template) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        player.sendMessage(this.miniMessage.deserialize(template, prefixResolver));
    }

    private void sendMessage(Player player, String template, String key, String value) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safeValue = value != null ? value : "";
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        TagResolver valueResolver = Placeholder.unparsed(key, safeValue);
        TagResolver combined = TagResolver.resolver(prefixResolver, valueResolver);
        player.sendMessage(this.miniMessage.deserialize(template, combined));
    }

    private void sendMessage(Player player, String template, String key1, String value1, String key2, String value2) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safe1 = value1 != null ? value1 : "";
        String safe2 = value2 != null ? value2 : "";
        TagResolver prefixResolver = Placeholder.parsed("prefix", this.config().messages().prefix());
        TagResolver combined = TagResolver.resolver(
            prefixResolver,
            Placeholder.unparsed(key1, safe1),
            Placeholder.unparsed(key2, safe2)
        );
        player.sendMessage(this.miniMessage.deserialize(template, combined));
    }

    @Override
    public void shutdown() {
        if (this.sweeperTask != null) {
            this.sweeperTask.cancel();
        }
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.taskRef() != null && warmup.taskRef().get() != null) {
                warmup.taskRef().get().cancel();
            }
            Player player = Bukkit.getPlayer(warmup.teleportingPlayerId());
            if (player != null && player.isOnline()) {
                if (warmup.bossBar() != null) {
                    player.hideBossBar(warmup.bossBar());
                }
                if (this.config().enableTitle()) {
                    player.clearTitle();
                }
            }
        }
        this.activeWarmups.clear();
        this.repository.clear();
    }
}
