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
import com.lunatech.tpcore.util.MessageFormatter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound.Source;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import static net.kyori.adventure.sound.Sound.sound;

import java.lang.reflect.Method;
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
    private final Map<UUID, Long> teleportProtectionMap = new ConcurrentHashMap<>();
    private final Map<String, Key> soundKeyCache = new ConcurrentHashMap<>();
    private final ScheduledTask sweeperTask;
    private static final Title.Times WARMUP_TITLE_TIMES = Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(200));
    private static final Vector ZERO_VECTOR = new Vector(0, 0, 0);

    private static final class ActiveWarmup {
        private final UUID teleportingPlayerId;
        private final UUID destinationPlayerId;
        private final String destinationPlayerName;
        private final String worldName;
        private final double startX;
        private final double startY;
        private final double startZ;
        private final int totalWarmupSeconds;
        private final BossBar bossBar;
        private int remainingSeconds;
        private volatile boolean cancelled;
        private volatile ScheduledTask task;

        ActiveWarmup(
            UUID teleportingPlayerId,
            UUID destinationPlayerId,
            String destinationPlayerName,
            String worldName,
            double startX,
            double startY,
            double startZ,
            int totalWarmupSeconds,
            BossBar bossBar
        ) {
            this.teleportingPlayerId = teleportingPlayerId;
            this.destinationPlayerId = destinationPlayerId;
            this.destinationPlayerName = destinationPlayerName;
            this.worldName = worldName;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.totalWarmupSeconds = totalWarmupSeconds;
            this.remainingSeconds = totalWarmupSeconds;
            this.bossBar = bossBar;
        }

        public UUID teleportingPlayerId() { return teleportingPlayerId; }
        public UUID destinationPlayerId() { return destinationPlayerId; }
        public String destinationPlayerName() { return destinationPlayerName; }
        public BossBar bossBar() { return bossBar; }
        public int remainingSeconds() { return remainingSeconds; }
        public int decrementRemainingSeconds() { return --remainingSeconds; }
        public boolean isCancelled() { return cancelled; }
        public void markCancelled() { this.cancelled = true; }
        public ScheduledTask task() { return task; }
        public void setTask(ScheduledTask task) { this.task = task; }

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
        this.loadOnlinePlayersSettings();
    }

    private void loadOnlinePlayersSettings() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null && player.isOnline()) {
                this.handlePlayerJoin(player);
            }
        }
    }

    private TpaConfig config() {
        return this.configRef.get();
    }

    @Override
    public void updateConfig(TpaConfig newConfig) {
        if (newConfig != null) {
            this.configRef.set(newConfig);
            this.soundKeyCache.clear();
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
                        if (!this.repository.removeRequest(request.targetId(), request.senderId())) {
                            continue;
                        }

                        Player sender = Bukkit.getPlayer(request.senderId());
                        if (sender != null && sender.isOnline()) {
                            Player target = Bukkit.getPlayer(request.targetId());
                            String targetName = (target != null && target.isOnline()) ? target.getName() : null;
                            if (targetName == null) {
                                OfflinePlayer op = resolveOfflinePlayerIfCached(request.targetId());
                                targetName = (op != null) ? op.getName() : null;
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
                                OfflinePlayer op = resolveOfflinePlayerIfCached(request.senderId());
                                senderName = (op != null) ? op.getName() : null;
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

        TpaUserSettings settings = new TpaUserSettings(toggledOff, autoAccept, blockedSet);
        this.repository.setUserSettings(player.getUniqueId(), settings);
    }

    private void saveUserSettingsToPdc(Player player) {
        if (player == null || !player.isOnline()) {
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

    @Override
    public void sendRequest(Player sender, Player target, TpaType type) {
        processSingleSendRequest(sender, target, type, true, false);
    }

    @Override
    public void sendRequest(Player sender, UUID targetId, TpaType type) {
        if (sender == null || targetId == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target != null && target.isOnline()) {
            sendRequest(sender, target, type);
        } else if (sender.isOnline()) {
            OfflinePlayer op = resolveOfflinePlayerIfCached(targetId);
            String targetName = (op != null && op.getName() != null) ? op.getName() : "Player";
            this.sendMessage(sender, this.config().messages().playerNotOnline(), "player", targetName);
        }
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
                if (isPlayerInWarmup(sender.getUniqueId())) {
                    break;
                }
                if (isPlayerInWarmup(target.getUniqueId())) {
                    continue;
                }
                boolean isAutoAccept = this.repository.isAutoAcceptEnabled(target.getUniqueId());
                if (processSingleSendRequest(sender, target, type, false, true)) {
                    sentAny = true;
                    if (isAutoAccept || isPlayerInWarmup(sender.getUniqueId())) {
                        break;
                    }
                }
            }
        }

        if (sentAny && cooldownSeconds > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
            this.repository.setCooldownEnd(sender.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        }
    }

    private boolean processSingleSendRequest(Player sender, Player target, TpaType type, boolean applyCooldown, boolean isBulk) {
        if (sender == null || target == null || !target.isOnline()) {
            if (!isBulk && sender != null && sender.isOnline()) {
                String targetName = (target != null && target.getName() != null) ? target.getName() : "Player";
                this.sendMessage(sender, this.config().messages().playerNotOnline(), "player", targetName);
            }
            return false;
        }

        if (!this.config().allowSelfTpa() && sender.getUniqueId().equals(target.getUniqueId())) {
            if (!isBulk) {
                this.sendMessage(sender, this.config().messages().rejectSelfTpa());
            }
            return false;
        }

        if (this.repository.isTpaToggledOff(target.getUniqueId())
            || this.repository.isPlayerBlocked(target.getUniqueId(), sender.getUniqueId())
            || this.repository.isPlayerBlocked(sender.getUniqueId(), target.getUniqueId())) {
            if (!isBulk) {
                this.sendMessage(
                    sender,
                    this.config().messages().targetToggledOff(),
                    "target", target.getName()
                );
            }
            return false;
        }

        if (this.repository.isAutoAcceptEnabled(target.getUniqueId())) {
            if (isPlayerInWarmup(sender.getUniqueId()) || isPlayerInWarmup(target.getUniqueId())) {
                return false;
            }
            int cooldownSecs = this.config().requestCooldownSeconds();
            if (applyCooldown && cooldownSecs > 0 && !sender.hasPermission(Permissions.TPA_BYPASS_COOLDOWN)) {
                this.repository.setCooldownEnd(sender.getUniqueId(), System.currentTimeMillis() + cooldownSecs * 1000L);
            }
            TpaRequest req = new TpaRequest(sender.getUniqueId(), target.getUniqueId(), type, System.currentTimeMillis());
            this.repository.addRequest(req);
            this.sendMessage(sender, this.config().messages().requestAutoAcceptedSender(), "target", target.getName());
            target.getScheduler().run(
                this.plugin,
                tTask -> this.sendMessage(target, this.config().messages().requestAutoAcceptedTarget(), "sender", sender.getName()),
                null
            );
            this.acceptRequestInternal(target, sender.getName(), false);
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
            if (!isBulk) {
                this.sendMessage(
                    sender,
                    this.config().messages().alreadyHasPendingRequest(),
                    "target", target.getName()
                );
            }
            return false;
        }

        int maxRequests = this.config().maxPendingRequestsPerPlayer();
        if (maxRequests > 0) {
            Collection<TpaRequest> incoming = this.repository.getIncomingRequests(target.getUniqueId());
            int activeCount = 0;
            int timeoutSeconds = this.config().requestTimeoutSeconds();
            for (TpaRequest req : incoming) {
                if (!req.isExpired(timeoutSeconds)) {
                    activeCount++;
                }
            }
            if (activeCount >= maxRequests) {
                if (!isBulk) {
                    this.sendMessage(
                        sender,
                        this.config().messages().maxPendingRequestsReached(),
                        "target", target.getName()
                    );
                }
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
        acceptRequestInternal(target, optionalSenderName, true);
    }

    private void acceptRequestInternal(Player target, String optionalSenderName, boolean notifyMessages) {
        Collection<TpaRequest> rawIncoming = this.repository.getIncomingRequests(target.getUniqueId());

        if (rawIncoming.isEmpty()) {
            if (notifyMessages) {
                this.sendMessage(target, this.config().messages().noPendingRequests());
            }
            return;
        }

        List<TpaRequest> incoming = new ArrayList<>();
        int timeoutSeconds = this.config().requestTimeoutSeconds();
        for (TpaRequest req : rawIncoming) {
            if (req != null && !req.isExpired(timeoutSeconds)) {
                incoming.add(req);
            } else if (req != null) {
                this.repository.removeRequest(req.targetId(), req.senderId());
            }
        }

        if (incoming.isEmpty()) {
            if (notifyMessages) {
                this.sendMessage(target, this.config().messages().noPendingRequests());
            }
            return;
        }

        TpaRequest targetRequest = null;

        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            targetRequest = resolveMatchingRequest(incoming, optionalSenderName);
        } else if (incoming.size() == 1 || !notifyMessages) {
            targetRequest = incoming.get(0);
        } else {
            if (notifyMessages) {
                this.sendMessage(target, this.config().messages().multiplePendingRequests());
            }
            return;
        }

        if (targetRequest == null || targetRequest.isExpired(this.config().requestTimeoutSeconds())) {
            if (notifyMessages) {
                this.sendMessage(target, this.config().messages().noPendingRequests());
            }
            if (targetRequest != null) {
                this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId());
            }
            return;
        }

        if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
            if (notifyMessages) {
                this.sendMessage(target, this.config().messages().noPendingRequests());
            }
            return;
        }

        Player sender = Bukkit.getPlayer(targetRequest.senderId());
        if (sender == null || !sender.isOnline()) {
            if (notifyMessages) {
                OfflinePlayer op = resolveOfflinePlayerIfCached(targetRequest.senderId());
                String senderName = (op != null && op.getName() != null) ? op.getName() : "Player";
                this.sendMessage(
                    target,
                    this.config().messages().playerNotOnline(),
                    "player", senderName
                );
            }
            return;
        }

        if (notifyMessages) {
            this.sendMessage(
                target,
                this.config().messages().requestAcceptedTarget(),
                "sender", sender.getName()
            );

            sender.getScheduler().run(
                this.plugin,
                sTask -> this.sendMessage(
                    sender,
                    this.config().messages().requestAcceptedSender(),
                    "target", target.getName()
                ),
                null
            );
        }

        Player teleportingPlayer = (targetRequest.type() == TpaType.TPA_TO) ? sender : target;
        Player destinationPlayer = (targetRequest.type() == TpaType.TPA_TO) ? target : sender;

        teleportingPlayer.getScheduler().run(
            this.plugin,
            tTask -> this.executeTeleportSequence(teleportingPlayer, destinationPlayer),
            null
        );
    }

    @Override
    public void denyRequest(Player target, String optionalSenderName) {
        Collection<TpaRequest> rawIncoming = this.repository.getIncomingRequests(target.getUniqueId());
        if (rawIncoming.isEmpty()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        List<TpaRequest> incoming = new ArrayList<>();
        int timeoutSeconds = this.config().requestTimeoutSeconds();
        for (TpaRequest req : rawIncoming) {
            if (req != null && !req.isExpired(timeoutSeconds)) {
                incoming.add(req);
            } else if (req != null) {
                this.repository.removeRequest(req.targetId(), req.senderId());
            }
        }

        if (incoming.isEmpty()) {
            this.sendMessage(target, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalSenderName != null && !optionalSenderName.isBlank()) {
            targetRequest = resolveMatchingRequest(incoming, optionalSenderName);
        } else if (incoming.size() == 1) {
            targetRequest = incoming.get(0);
        } else {
            this.sendMessage(target, this.config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest != null) {
            if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
                this.sendMessage(target, this.config().messages().noPendingRequests());
                return;
            }
            this.repository.setCooldownEnd(targetRequest.senderId(), 0L);
            Player sender = Bukkit.getPlayer(targetRequest.senderId());
            if (sender != null && sender.isOnline()) {
                UUID reqTargetId = targetRequest.targetId();
                final String targetDisplayName = target.getName();
                sender.getScheduler().run(
                    this.plugin,
                    t -> {
                        this.closeConfirmationMenuIfOpen(sender, reqTargetId);
                        this.sendMessage(
                            sender,
                            this.config().messages().requestDeniedSender(),
                            "target", targetDisplayName
                        );
                        if (this.config().enableSounds()) {
                            TpaConfig cfg = this.config();
                            playSound(sender, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                        }
                    },
                    null
                );
            }
            this.closeConfirmationMenuIfOpen(target, targetRequest.senderId());
            String senderDisplayName = (sender != null) ? sender.getName() : null;
            if (senderDisplayName == null) {
                OfflinePlayer op = resolveOfflinePlayerIfCached(targetRequest.senderId());
                senderDisplayName = (op != null && op.getName() != null) ? op.getName() : "Player";
            }
            this.sendMessage(
                target,
                this.config().messages().requestDeniedTarget(),
                "sender", (senderDisplayName != null) ? senderDisplayName : "Player"
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
        Collection<TpaRequest> rawOutgoing = this.repository.getOutgoingRequests(sender.getUniqueId());
        if (rawOutgoing.isEmpty()) {
            this.sendMessage(sender, this.config().messages().noPendingRequests());
            return;
        }

        List<TpaRequest> outgoing = new ArrayList<>();
        int timeoutSeconds = this.config().requestTimeoutSeconds();
        for (TpaRequest req : rawOutgoing) {
            if (req != null && !req.isExpired(timeoutSeconds)) {
                outgoing.add(req);
            } else if (req != null) {
                this.repository.removeRequest(req.targetId(), req.senderId());
            }
        }

        if (outgoing.isEmpty()) {
            this.sendMessage(sender, this.config().messages().noPendingRequests());
            return;
        }

        TpaRequest targetRequest = null;
        if (optionalTargetName != null && !optionalTargetName.isBlank()) {
            targetRequest = resolveMatchingOutgoingRequest(outgoing, optionalTargetName);
        } else if (outgoing.size() == 1) {
            targetRequest = outgoing.get(0);
        } else {
            this.sendMessage(sender, this.config().messages().multiplePendingRequests());
            return;
        }

        if (targetRequest != null) {
            if (!this.repository.removeRequest(targetRequest.targetId(), targetRequest.senderId())) {
                this.sendMessage(sender, this.config().messages().noPendingRequests());
                return;
            }
            this.repository.setCooldownEnd(targetRequest.senderId(), 0L);
            Player target = Bukkit.getPlayer(targetRequest.targetId());
            if (target != null && target.isOnline()) {
                UUID reqSenderId = targetRequest.senderId();
                final String senderDisplayName = sender.getName();
                target.getScheduler().run(
                    this.plugin,
                    t -> {
                        this.closeConfirmationMenuIfOpen(target, reqSenderId);
                        this.sendMessage(
                            target,
                            this.config().messages().requestCancelledTarget(),
                            "sender", senderDisplayName
                        );
                        if (this.config().enableSounds()) {
                            TpaConfig cfg = this.config();
                            playSound(target, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                        }
                    },
                    null
                );
            }
            this.closeConfirmationMenuIfOpen(sender, targetRequest.targetId());
            String targetDisplayName = (target != null) ? target.getName() : null;
            if (targetDisplayName == null) {
                OfflinePlayer op = resolveOfflinePlayerIfCached(targetRequest.targetId());
                targetDisplayName = (op != null && op.getName() != null) ? op.getName() : "Player";
            }
            this.sendMessage(
                sender,
                this.config().messages().requestCancelledSender(),
                "target", (targetDisplayName != null) ? targetDisplayName : "Player"
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
            this.repository.removeAllRequestsForPlayer(player.getUniqueId());
            this.cancelWarmup(player.getUniqueId(), null);
            this.cancelWarmupsForDestination(player.getUniqueId(), this.config().messages().targetToggledOff());
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
            int prevSize = -1;
            while (this.repository.isAutoAcceptEnabled(player.getUniqueId())
                    && !this.repository.getIncomingRequests(player.getUniqueId()).isEmpty()
                    && !this.isPlayerInWarmup(player.getUniqueId())) {
                int size = this.repository.getIncomingRequests(player.getUniqueId()).size();
                if (size == prevSize) {
                    break;
                }
                prevSize = size;
                this.acceptRequestInternal(player, null, false);
            }
            if (this.isPlayerInWarmup(player.getUniqueId())) {
                this.repository.removeAllRequestsForPlayer(player.getUniqueId());
            }
        } else {
            this.sendMessage(player, this.config().messages().autoAcceptOff());
        }
        return newStatus;
    }

    private static final Method GET_OFFLINE_PLAYER_IF_CACHED;
    private static final Method GET_OFFLINE_PLAYER_IF_CACHED_UUID;
    static {
        Method mName = null;
        Method mUuid = null;
        try {
            mName = Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class);
            mName.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            mUuid = Bukkit.class.getMethod("getOfflinePlayerIfCached", UUID.class);
            mUuid.setAccessible(true);
        } catch (Throwable ignored) {
        }
        GET_OFFLINE_PLAYER_IF_CACHED = mName;
        GET_OFFLINE_PLAYER_IF_CACHED_UUID = mUuid;
    }

    private static OfflinePlayer resolveOfflinePlayerIfCached(String name) {
        if (GET_OFFLINE_PLAYER_IF_CACHED != null && name != null) {
            try {
                return (OfflinePlayer) GET_OFFLINE_PLAYER_IF_CACHED.invoke(null, name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static OfflinePlayer resolveOfflinePlayerIfCached(UUID uuid) {
        if (GET_OFFLINE_PLAYER_IF_CACHED_UUID != null && uuid != null) {
            try {
                OfflinePlayer op = (OfflinePlayer) GET_OFFLINE_PLAYER_IF_CACHED_UUID.invoke(null, uuid);
                if (op != null && op.getName() != null) {
                    return op;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Override
    public void blockPlayer(Player player, String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            target = Bukkit.getPlayer(targetName);
        }
        OfflinePlayer offlineTarget = (target == null) ? resolveOfflinePlayerIfCached(targetName) : null;
        UUID targetId = (target != null) ? target.getUniqueId() : (offlineTarget != null ? offlineTarget.getUniqueId() : null);

        if (targetId == null) {
            String trimmed = targetName.trim();
            try {
                targetId = UUID.fromString(trimmed);
            } catch (Throwable ignored) {
            }
        }

        if (targetId == null) {
            this.sendMessage(player, this.config().messages().playerNotOnline(), "player", targetName);
            return;
        }

        if (offlineTarget == null && target == null) {
            offlineTarget = resolveOfflinePlayerIfCached(targetId);
        }

        if (player.getUniqueId().equals(targetId)) {
            this.sendMessage(player, this.config().messages().rejectSelfTpa());
            return;
        }

        this.repository.setPlayerBlocked(player.getUniqueId(), targetId, true);
        this.repository.removeRequest(player.getUniqueId(), targetId);
        this.repository.removeRequest(targetId, player.getUniqueId());
        this.cancelWarmup(player.getUniqueId(), null);
        this.cancelWarmup(targetId, null);
        this.closeConfirmationMenuIfOpen(player, targetId);
        if (target != null && target.isOnline()) {
            final Player finalTarget = target;
            target.getScheduler().run(
                this.plugin,
                t -> this.closeConfirmationMenuIfOpen(finalTarget, player.getUniqueId()),
                null
            );
        }
        this.saveUserSettingsToPdc(player);

        String displayName = (target != null) ? target.getName() : ((offlineTarget != null && offlineTarget.getName() != null) ? offlineTarget.getName() : targetName);
        this.sendMessage(player, this.config().messages().playerBlocked(), "player", displayName);
    }

    @Override
    public void unblockPlayer(Player player, String targetName) {
        if (player == null || targetName == null || targetName.isBlank()) {
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            target = Bukkit.getPlayer(targetName);
        }
        OfflinePlayer offlineTarget = (target == null) ? resolveOfflinePlayerIfCached(targetName) : null;
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
            Set<UUID> blocked = this.getBlockedPlayers(player);
            for (UUID bId : blocked) {
                String bStr = bId.toString();
                if (bStr.equalsIgnoreCase(trimmed) || bStr.startsWith(trimmed)) {
                    targetId = bId;
                    break;
                }
            }
        }

        if (targetId == null || !this.repository.isPlayerBlocked(player.getUniqueId(), targetId)) {
            this.sendMessage(player, this.config().messages().notBlocked(), "player", targetName);
            return;
        }

        if (offlineTarget == null && target == null) {
            offlineTarget = resolveOfflinePlayerIfCached(targetId);
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
                OfflinePlayer op = resolveOfflinePlayerIfCached(uuid);
                String name = (op != null) ? op.getName() : null;
                names.add(name != null ? name : uuid.toString().substring(0, 8) + "...");
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
                OfflinePlayer op = resolveOfflinePlayerIfCached(req.senderId());
                String cachedName = (op != null) ? op.getName() : null;
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
                OfflinePlayer op = resolveOfflinePlayerIfCached(req.targetId());
                String cachedName = (op != null) ? op.getName() : null;
                if (cachedName != null && cachedName.equalsIgnoreCase(trimmed)) {
                    return req;
                }
            }
        }
        return null;
    }

    @Override
    public void handlePlayerQuit(Player player) {
        if (player != null) {
            this.saveUserSettingsToPdc(player);
            this.handlePlayerQuit(player.getUniqueId());
        }
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        if (playerId == null) {
            return;
        }
        this.teleportProtectionMap.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            this.saveUserSettingsToPdc(player);
        }

        String quitName = (player != null && player.getName() != null) ? player.getName() : null;
        if (quitName == null) {
            OfflinePlayer op = resolveOfflinePlayerIfCached(playerId);
            quitName = (op != null && op.getName() != null) ? op.getName() : "Player";
        }
        final String displayName = quitName;

        Collection<TpaRequest> outgoing = this.repository.getOutgoingRequests(playerId);
        if (outgoing != null && !outgoing.isEmpty()) {
            for (TpaRequest req : outgoing) {
                Player target = Bukkit.getPlayer(req.targetId());
                if (target != null && target.isOnline()) {
                    target.getScheduler().run(
                        this.plugin,
                        t -> {
                            this.closeConfirmationMenuIfOpen(target, playerId);
                            this.sendMessage(
                                target,
                                this.config().messages().requestCancelledTarget(),
                                "sender", displayName
                            );
                        },
                        null
                    );
                }
            }
        }

        Collection<TpaRequest> incoming = this.repository.getIncomingRequests(playerId);
        if (incoming != null && !incoming.isEmpty()) {
            for (TpaRequest req : incoming) {
                Player sender = Bukkit.getPlayer(req.senderId());
                if (sender != null && sender.isOnline()) {
                    sender.getScheduler().run(
                        this.plugin,
                        t -> {
                            this.closeConfirmationMenuIfOpen(sender, playerId);
                            this.sendMessage(
                                sender,
                                this.config().messages().requestCancelledSender(),
                                "target", displayName
                            );
                        },
                        null
                    );
                }
            }
        }

        this.repository.removeAllRequestsForPlayer(playerId);
        this.repository.setUserSettings(playerId, null);
        this.repository.setCooldownEnd(playerId, 0L);
        this.cancelWarmup(playerId, null);
        this.cancelWarmupsForDestination(playerId, this.config().messages().playerNotOnline());
    }

    @Override
    public void handlePlayerDamage(UUID playerId) {
        if (this.config().cancelOnDamage()) {
            this.cancelWarmup(playerId, this.config().messages().warmupCancelledDamage());
            this.cancelWarmupsForDestination(playerId, this.config().messages().warmupCancelledDamage());
        }
    }

    @Override
    public void handlePlayerMove(Player player) {
        if (player != null && player.isOnline()) {
            handlePlayerMove(player, player.getLocation());
        }
    }

    @Override
    public void handlePlayerMove(Player player, Location to) {
        if (!this.config().cancelOnMove() || this.activeWarmups.isEmpty() || player == null || to == null) {
            return;
        }
        if (!this.activeWarmups.containsKey(player.getUniqueId())) {
            return;
        }
        ActiveWarmup warmup = this.activeWarmups.get(player.getUniqueId());
        if (warmup != null && warmup.hasMoved(to)) {
            this.cancelWarmup(player.getUniqueId(), this.config().messages().warmupCancelledMove());
        }
    }

    @Override
    public void handlePlayerTeleport(UUID playerId) {
        this.cancelWarmup(playerId, null);
        this.cancelWarmupsForDestination(playerId, this.config().messages().requestCancelledTarget());
    }

    @Override
    public void handlePlayerDeath(UUID playerId) {
        this.repository.removeAllRequestsForPlayer(playerId);
        this.teleportProtectionMap.remove(playerId);
        this.cancelWarmup(playerId, null);
        this.cancelWarmupsForDestination(playerId, this.config().messages().playerNotOnline());
    }

    private boolean isPlayerInWarmup(UUID playerId) {
        if (playerId == null || this.activeWarmups.isEmpty()) {
            return false;
        }
        if (this.activeWarmups.containsKey(playerId)) {
            return true;
        }
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup != null && playerId.equals(warmup.destinationPlayerId())) {
                return true;
            }
        }
        return false;
    }

    private void cancelWarmupsForDestination(UUID destinationId, String cancelMessage) {
        if (this.activeWarmups.isEmpty() || destinationId == null) {
            return;
        }
        List<UUID> toCancel = new ArrayList<>();
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup != null && destinationId.equals(warmup.destinationPlayerId())) {
                toCancel.add(warmup.teleportingPlayerId());
            }
        }
        for (UUID playerId : toCancel) {
            this.cancelWarmup(playerId, cancelMessage);
        }
    }

    private void executeTeleportSequence(Player player, Player destinationPlayer) {
        if (player == null || !player.isOnline() || player.isDead() || destinationPlayer == null || !destinationPlayer.isOnline() || destinationPlayer.isDead()) {
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
            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
            TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(warmupSeconds));
            bossBar = BossBar.bossBar(
                this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.bossbarFormat()), TagResolver.resolver(prefixResolver, secResolver)),
                1.0f,
                color,
                overlay
            );
            player.showBossBar(bossBar);
        }

        Location currentLoc = player.getLocation();
        BossBar finalBossBar = bossBar;

        ActiveWarmup warmup = new ActiveWarmup(
            player.getUniqueId(),
            destinationPlayer.getUniqueId(),
            destinationPlayer.getName(),
            currentLoc.getWorld().getName(),
            currentLoc.getX(),
            currentLoc.getY(),
            currentLoc.getZ(),
            warmupSeconds,
            finalBossBar
        );
        this.activeWarmups.put(player.getUniqueId(), warmup);

        updateWarmupFeedback(player, warmupSeconds, warmupSeconds);

        ScheduledTask task = player.getScheduler().runAtFixedRate(
            this.plugin,
            scheduledTask -> {
                if (!player.isOnline() || !destinationPlayer.isOnline() || warmup.isCancelled()) {
                    scheduledTask.cancel();
                    String cancelMsg = (!destinationPlayer.isOnline() && player.isOnline()) ? this.config().messages().playerNotOnline() : null;
                    this.cancelWarmup(player.getUniqueId(), cancelMsg);
                    return;
                }

                int rem = warmup.decrementRemainingSeconds();
                if (rem > 0) {
                    if (warmup.isCancelled()) {
                        scheduledTask.cancel();
                        return;
                    }
                    updateWarmupFeedback(player, rem, warmupSeconds);
                    if (finalBossBar != null) {
                        float progress = Math.max(0.0f, Math.min(1.0f, (float) rem / (float) warmupSeconds));
                        finalBossBar.progress(progress);
                        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(rem));
                        finalBossBar.name(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.bossbarFormat()), TagResolver.resolver(prefixResolver, secResolver)));
                    }
                } else {
                    scheduledTask.cancel();
                    ActiveWarmup removed = this.activeWarmups.remove(player.getUniqueId());
                    if (removed != null && !removed.isCancelled()) {
                        removed.markCancelled();
                        if (finalBossBar != null) {
                            player.hideBossBar(finalBossBar);
                        }
                        if (cfg.enableTitle()) {
                            player.clearTitle();
                        }
                        performFinalTeleport(player, destinationPlayer);
                    }
                }
            },
            () -> this.cancelWarmup(player.getUniqueId(), null),
            20L,
            20L
        );

        warmup.setTask(task);
        if (warmup.isCancelled()) {
            if (task != null) {
                task.cancel();
            }
            this.cancelWarmup(player.getUniqueId(), null);
        }
    }

    private void updateWarmupFeedback(Player player, int remainingSeconds, int totalWarmupSeconds) {
        TpaConfig cfg = this.config();
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
        TagResolver secResolver = Placeholder.unparsed("seconds", String.valueOf(remainingSeconds));
        TagResolver combined = TagResolver.resolver(prefixResolver, secResolver);

        if (cfg.enableActionBar()) {
            player.sendActionBar(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.actionBarFormat()), combined));
        }

        if (cfg.enableTitle()) {
            Title title = Title.title(
                this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.titleFormat()), combined),
                this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cfg.subtitleFormat()), combined),
                WARMUP_TITLE_TIMES
            );
            player.showTitle(title);
        }

        if (cfg.enableSounds()) {
            int elapsed = totalWarmupSeconds - remainingSeconds;
            float progress = (totalWarmupSeconds > 0) ? (float) elapsed / (float) totalWarmupSeconds : 1.0f;
            float rawPitch = 1.0f + progress;
            playSound(player, cfg.tickSound(), (float) cfg.tickSoundVolume(), rawPitch);
        }
    }

    private void playSound(Player player, String soundKey, float volume, float pitch) {
        if (player == null || !player.isOnline() || soundKey == null || soundKey.isBlank()) {
            return;
        }
        try {
            Key key = resolveSoundKey(soundKey);
            if (key != null) {
                float clampedVol = Math.max(0.0f, volume);
                float clampedPitch = Math.max(0.5f, Math.min(2.0f, pitch));
                player.playSound(sound(key, Source.PLAYER, clampedVol, clampedPitch));
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Key INVALID_SOUND_KEY = Key.key("tpcore", "invalid_sound");

    private Key resolveSoundKey(String soundKey) {
        if (soundKey == null || soundKey.isBlank()) {
            return null;
        }
        Key cached = this.soundKeyCache.computeIfAbsent(soundKey, rawKey -> {
            String trimmed = rawKey.trim();
            try {
                Sound bukkitSound = Sound.valueOf(trimmed.toUpperCase(Locale.ROOT));
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
            }

            if (cleanKey.contains("_")) {
                try {
                    return Key.key(cleanKey.replace('_', '.'));
                } catch (Throwable ignored) {
                }
            }
            return INVALID_SOUND_KEY;
        });
        return (cached == INVALID_SOUND_KEY) ? null : cached;
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
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        if (destinationPlayer == null || !destinationPlayer.isOnline() || destinationPlayer.isDead()) {
            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
            String dName = (destinationPlayer != null && destinationPlayer.getName() != null) ? destinationPlayer.getName() : "Player";
            this.sendMessage(player, this.config().messages().playerNotOnline(), "player", dName);
            return;
        }

        destinationPlayer.getScheduler().run(
            this.plugin,
            destTask -> {
                if (!player.isOnline() || player.isDead() || !destinationPlayer.isOnline() || destinationPlayer.isDead()) {
                    return;
                }

                Location rawTargetLoc = destinationPlayer.getLocation();
                Location finalTargetLoc = rawTargetLoc;

                if (this.config().requireSafeLocation()) {
                    Location safeLoc = TpaSafetyInspector.findSafeLocation(rawTargetLoc);
                    if (safeLoc == null) {
                        this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                        this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                        player.getScheduler().run(
                            this.plugin,
                            pTask -> this.sendMessage(player, this.config().messages().unsafeDestination()),
                            null
                        );
                        this.sendMessage(destinationPlayer, this.config().messages().unsafeDestination());
                        return;
                    }
                    finalTargetLoc = safeLoc;
                }

                Location destination = finalTargetLoc;
                player.getScheduler().run(
                    this.plugin,
                    playerTask -> {
                        if (!player.isOnline() || player.isDead()) {
                            this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                            if (destinationPlayer.isOnline()) {
                                OfflinePlayer op = resolveOfflinePlayerIfCached(player.getUniqueId());
                                String pName = (op != null && op.getName() != null) ? op.getName() : "Player";
                                this.sendMessage(
                                    destinationPlayer,
                                    this.config().messages().playerNotOnline(),
                                    "player", pName
                                );
                            }
                            return;
                        }
                        if (!destinationPlayer.isOnline() || destinationPlayer.isDead() || destination.getWorld() == null) {
                            return;
                        }
                        if (player.isInsideVehicle()) {
                            player.leaveVehicle();
                        }
                        player.eject();
                        player.setVelocity(ZERO_VECTOR);
                        player.teleportAsync(destination).thenAccept(success -> {
                            if (player.isOnline()) {
                                player.getScheduler().run(
                                    this.plugin,
                                    compTask -> {
                                        if (success) {
                                            player.setFallDistance(0.0f);
                                            player.setFireTicks(0);
                                            grantTeleportProtection(player);
                                            TpaConfig cfg = this.config();
                                            if (cfg.enableSounds()) {
                                                playSound(player, cfg.completionSound(), (float) cfg.completionSoundVolume(), (float) cfg.completionSoundPitch());
                                            }
                                        } else {
                                            this.repository.setCooldownEnd(player.getUniqueId(), 0L);
                                            this.repository.setCooldownEnd(destinationPlayer.getUniqueId(), 0L);
                                            this.sendMessage(player, this.config().messages().unsafeDestination());
                                            if (destinationPlayer.isOnline()) {
                                                destinationPlayer.getScheduler().run(
                                                    this.plugin,
                                                    dTask -> this.sendMessage(destinationPlayer, this.config().messages().unsafeDestination()),
                                                    null
                                                );
                                            }
                                        }
                                    },
                                    null
                                );
                            }
                        });
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
            warmup.markCancelled();
            if (warmup.task() != null) {
                warmup.task().cancel();
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                final String dName = (warmup.destinationPlayerName() != null && !warmup.destinationPlayerName().isBlank())
                    ? warmup.destinationPlayerName()
                    : "Player";
                player.getScheduler().run(
                    this.plugin,
                    pTask -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (warmup.bossBar() != null) {
                            player.hideBossBar(warmup.bossBar());
                        }
                        TpaConfig cfg = this.config();
                        if (cfg.enableSounds()) {
                            playSound(player, cfg.cancelSound(), (float) cfg.cancelSoundVolume(), (float) cfg.cancelSoundPitch());
                        }
                        if (cancelMessageTemplate != null && !cancelMessageTemplate.isBlank()) {
                            TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(cfg.messages().prefix()));
                            TagResolver playerResolver = Placeholder.unparsed("player", dName);
                            TagResolver targetResolver = Placeholder.unparsed("target", dName);
                            TagResolver combined = TagResolver.resolver(prefixResolver, playerResolver, targetResolver);

                            net.kyori.adventure.text.Component msgComp = this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cancelMessageTemplate), combined);
                            player.sendMessage(msgComp);

                            if (cfg.enableActionBar()) {
                                player.sendActionBar(msgComp);
                            }

                            if (cfg.enableTitle()) {
                                String cancelTitleTpl = (cfg.cancelTitleFormat() != null && !cfg.cancelTitleFormat().isBlank())
                                    ? cfg.cancelTitleFormat()
                                    : "<red><bold>TPA CANCELLED</bold></red>";
                                net.kyori.adventure.text.Component titleComp = this.miniMessage.deserialize(MessageFormatter.toMiniMessage(cancelTitleTpl), combined);
                                Title title = Title.title(
                                    titleComp,
                                    msgComp,
                                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))
                                );
                                player.showTitle(title);
                            }
                        } else if (cfg.enableTitle()) {
                            player.clearTitle();
                        }
                    },
                    null
                );
            }
            if (warmup.destinationPlayerId() != null) {
                Player dest = Bukkit.getPlayer(warmup.destinationPlayerId());
                if (dest != null && dest.isOnline()) {
                    String pName = (player != null && player.getName() != null) ? player.getName() : null;
                    if (pName == null) {
                        OfflinePlayer op = resolveOfflinePlayerIfCached(playerId);
                        pName = (op != null && op.getName() != null) ? op.getName() : "Player";
                    }
                    final String teleporterName = pName;
                    dest.getScheduler().run(
                        this.plugin,
                        dTask -> this.sendMessage(
                            dest,
                            this.config().messages().requestCancelledTarget(),
                            "sender", teleporterName
                        ),
                        null
                    );
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
        boolean matches = (expectedOtherPlayerId == null);
        try {
            InventoryView view = player.getOpenInventory();
            Inventory top = (view != null) ? view.getTopInventory() : null;
            if (top != null && top.getHolder() instanceof TpaConfirmationHolder holder) {
                if (expectedOtherPlayerId != null) {
                    if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.ACCEPT_REQUEST && holder.getRequest() != null) {
                        matches = expectedOtherPlayerId.equals(holder.getRequest().senderId());
                    } else if (holder.getConfirmationType() == TpaConfirmationHolder.ConfirmationType.SEND_REQUEST && holder.getTargetPlayerId() != null) {
                        matches = expectedOtherPlayerId.equals(holder.getTargetPlayerId());
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
        private static final Method CLOSE_DIALOG;
        static {
            Method m = null;
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
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(this.config().messages().prefix()));
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), prefixResolver));
    }

    private void sendMessage(Player player, String template, String key, String value) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safeValue = value != null ? value : "";
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(this.config().messages().prefix()));
        TagResolver valueResolver = Placeholder.unparsed(key, safeValue);
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), TagResolver.resolver(prefixResolver, valueResolver)));
    }

    private void sendMessage(Player player, String template, String key1, String value1, String key2, String value2) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        String safe1 = value1 != null ? value1 : "";
        String safe2 = value2 != null ? value2 : "";
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(this.config().messages().prefix()));
        player.sendMessage(this.miniMessage.deserialize(
            MessageFormatter.toMiniMessage(template),
            TagResolver.resolver(
                prefixResolver,
                Placeholder.unparsed(key1, safe1),
                Placeholder.unparsed(key2, safe2)
            )
        ));
    }

    @Override
    public void shutdown() {
        if (this.sweeperTask != null) {
            this.sweeperTask.cancel();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) {
                this.saveUserSettingsToPdc(p);
            }
        }
        for (ActiveWarmup warmup : this.activeWarmups.values()) {
            if (warmup.task() != null) {
                warmup.task().cancel();
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
        this.teleportProtectionMap.clear();
        this.soundKeyCache.clear();
        this.repository.clear();
    }

    @Override
    public void grantTeleportProtection(Player player) {
        if (player == null || !player.isOnline() || this.config().protectionSeconds() <= 0) {
            return;
        }
        int seconds = this.config().protectionSeconds();
        long expiry = System.currentTimeMillis() + (seconds * 1000L);
        this.teleportProtectionMap.put(player.getUniqueId(), expiry);
        this.sendMessage(
            player,
            this.config().messages().teleportProtectionStart(),
            "seconds", String.valueOf(seconds)
        );

        player.getScheduler().runDelayed(
            this.plugin,
            task -> {
                if (player.isOnline()) {
                    Long exp = this.teleportProtectionMap.get(player.getUniqueId());
                    if (exp != null && System.currentTimeMillis() >= exp) {
                        if (this.teleportProtectionMap.remove(player.getUniqueId(), exp)) {
                            this.sendMessage(player, this.config().messages().teleportProtectionEnded());
                        }
                    }
                }
            },
            null,
            seconds * 20L
        );
    }

    @Override
    public boolean hasTeleportProtection(UUID playerId) {
        if (playerId == null || this.teleportProtectionMap.isEmpty()) {
            return false;
        }
        Long expiry = this.teleportProtectionMap.get(playerId);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry) {
            if (this.teleportProtectionMap.remove(playerId, expiry)) {
                Player p = Bukkit.getPlayer(playerId);
                if (p != null && p.isOnline()) {
                    this.sendMessage(p, this.config().messages().teleportProtectionEnded());
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean hasActiveWarmups() {
        return !this.activeWarmups.isEmpty();
    }

    @Override
    public long getTeleportProtectionStartTime(UUID playerId) {
        if (playerId == null || this.teleportProtectionMap.isEmpty()) {
            return 0L;
        }
        Long expiry = this.teleportProtectionMap.get(playerId);
        if (expiry == null || System.currentTimeMillis() >= expiry) {
            return 0L;
        }
        int seconds = this.config().protectionSeconds();
        return expiry - (seconds * 1000L);
    }

    @Override
    public void stripTeleportProtection(UUID playerId) {
        if (playerId == null || this.teleportProtectionMap.isEmpty()) {
            return;
        }
        Long removed = this.teleportProtectionMap.remove(playerId);
        if (removed != null) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                this.sendMessage(p, this.config().messages().teleportProtectionEnded());
            }
        }
    }

    @Override
    public boolean handlePlayerProtectionDamage(Player victim, Player attacker, boolean isPvp) {
        if (attacker != null && this.config().protectionCancelOnAttack() && hasTeleportProtection(attacker.getUniqueId())) {
            stripTeleportProtection(attacker.getUniqueId());
        }

        if (victim != null) {
            if (!isPvp && !this.config().protectionAllDamage()) {
                return false;
            }
            return hasTeleportProtection(victim.getUniqueId());
        }
        return false;
    }
}
