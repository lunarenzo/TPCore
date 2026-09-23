package com.lunatech.tpcore.module.tpa.repository.impl;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaUserSettings;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ConcurrentTpaRepository implements TpaRepository {

    private final Map<UUID, Map<UUID, TpaRequest>> incoming = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, TpaRequest>> outgoing = new ConcurrentHashMap<>();
    private final Set<UUID> toggledOffPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TpaUserSettings> userSettingsMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownsMap = new ConcurrentHashMap<>();

    @Override
    public void addRequest(TpaRequest request) {
        this.incoming
            .computeIfAbsent(request.targetId(), k -> new ConcurrentHashMap<>())
            .put(request.senderId(), request);

        this.outgoing
            .computeIfAbsent(request.senderId(), k -> new ConcurrentHashMap<>())
            .put(request.targetId(), request);
    }

    @Override
    public Optional<TpaRequest> getRequest(UUID targetId, UUID senderId) {
        Map<UUID, TpaRequest> targetMap = this.incoming.get(targetId);
        if (targetMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(targetMap.get(senderId));
    }

    @Override
    public Collection<TpaRequest> getIncomingRequests(UUID targetId) {
        Map<UUID, TpaRequest> targetMap = this.incoming.get(targetId);
        if (targetMap == null || targetMap.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableCollection(targetMap.values());
    }

    @Override
    public Collection<TpaRequest> getOutgoingRequests(UUID senderId) {
        Map<UUID, TpaRequest> senderMap = this.outgoing.get(senderId);
        if (senderMap == null || senderMap.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableCollection(senderMap.values());
    }

    @Override
    public Collection<TpaRequest> getAllRequests() {
        List<TpaRequest> all = new ArrayList<>();
        for (Map<UUID, TpaRequest> map : this.incoming.values()) {
            all.addAll(map.values());
        }
        return Collections.unmodifiableCollection(all);
    }

    @Override
    public void forEachRequest(Consumer<TpaRequest> action) {
        if (action == null) {
            return;
        }
        for (Map<UUID, TpaRequest> map : this.incoming.values()) {
            for (TpaRequest request : map.values()) {
                action.accept(request);
            }
        }
    }

    @Override
    public void removeRequest(UUID targetId, UUID senderId) {
        this.incoming.computeIfPresent(targetId, (tId, targetMap) -> {
            targetMap.remove(senderId);
            return targetMap.isEmpty() ? null : targetMap;
        });

        this.outgoing.computeIfPresent(senderId, (sId, senderMap) -> {
            senderMap.remove(targetId);
            return senderMap.isEmpty() ? null : senderMap;
        });
    }

    @Override
    public void removeAllRequestsForPlayer(UUID playerId) {
        this.toggledOffPlayers.remove(playerId);
        this.userSettingsMap.remove(playerId);
        this.cooldownsMap.remove(playerId);

        Map<UUID, TpaRequest> inc = this.incoming.remove(playerId);
        if (inc != null) {
            for (UUID senderId : inc.keySet()) {
                this.outgoing.computeIfPresent(senderId, (sId, senderMap) -> {
                    senderMap.remove(playerId);
                    return senderMap.isEmpty() ? null : senderMap;
                });
            }
        }

        Map<UUID, TpaRequest> out = this.outgoing.remove(playerId);
        if (out != null) {
            for (UUID targetId : out.keySet()) {
                this.incoming.computeIfPresent(targetId, (tId, inMap) -> {
                    inMap.remove(playerId);
                    return inMap.isEmpty() ? null : inMap;
                });
            }
        }
    }

    @Override
    public boolean isTpaToggledOff(UUID playerId) {
        TpaUserSettings settings = this.userSettingsMap.get(playerId);
        if (settings != null) {
            return settings.toggledOff();
        }
        return this.toggledOffPlayers.contains(playerId);
    }

    @Override
    public void setTpaToggledOff(UUID playerId, boolean toggledOff) {
        if (toggledOff) {
            this.toggledOffPlayers.add(playerId);
        } else {
            this.toggledOffPlayers.remove(playerId);
        }
        this.userSettingsMap.compute(playerId, (id, current) -> {
            boolean autoAccept = (current != null) && current.autoAccept();
            Set<UUID> blocked = (current != null) ? current.blockedPlayers() : Collections.emptySet();
            return new TpaUserSettings(toggledOff, autoAccept, blocked);
        });
    }

    @Override
    public boolean isAutoAcceptEnabled(UUID playerId) {
        TpaUserSettings settings = this.userSettingsMap.get(playerId);
        return settings != null && settings.autoAccept();
    }

    @Override
    public void setAutoAcceptEnabled(UUID playerId, boolean autoAccept) {
        this.userSettingsMap.compute(playerId, (id, current) -> {
            boolean toggledOff = (current != null) && current.toggledOff();
            Set<UUID> blocked = (current != null) ? current.blockedPlayers() : Collections.emptySet();
            return new TpaUserSettings(toggledOff, autoAccept, blocked);
        });
    }

    @Override
    public TpaUserSettings getUserSettings(UUID playerId) {
        return this.userSettingsMap.getOrDefault(playerId, TpaUserSettings.createDefault());
    }

    @Override
    public void setUserSettings(UUID playerId, TpaUserSettings settings) {
        if (settings == null) {
            this.userSettingsMap.remove(playerId);
            this.toggledOffPlayers.remove(playerId);
            return;
        }
        this.userSettingsMap.put(playerId, settings);
        if (settings.toggledOff()) {
            this.toggledOffPlayers.add(playerId);
        } else {
            this.toggledOffPlayers.remove(playerId);
        }
    }

    @Override
    public boolean isPlayerBlocked(UUID targetId, UUID senderId) {
        TpaUserSettings settings = this.userSettingsMap.get(targetId);
        return settings != null && settings.isBlocked(senderId);
    }

    @Override
    public void setPlayerBlocked(UUID playerId, UUID targetId, boolean blocked) {
        this.userSettingsMap.compute(playerId, (id, current) -> {
            boolean toggledOff = (current != null) && current.toggledOff();
            boolean autoAccept = (current != null) && current.autoAccept();
            Set<UUID> blockedSet = new HashSet<>((current != null && current.blockedPlayers() != null) ? current.blockedPlayers() : Collections.emptySet());
            if (blocked) {
                blockedSet.add(targetId);
            } else {
                blockedSet.remove(targetId);
            }
            return new TpaUserSettings(toggledOff, autoAccept, Collections.unmodifiableSet(blockedSet));
        });
    }

    @Override
    public long getCooldownEnd(UUID senderId) {
        Long val = this.cooldownsMap.get(senderId);
        return val != null ? val : 0L;
    }

    @Override
    public void setCooldownEnd(UUID senderId, long endTimestamp) {
        if (endTimestamp <= System.currentTimeMillis()) {
            this.cooldownsMap.remove(senderId);
        } else {
            this.cooldownsMap.put(senderId, endTimestamp);
        }
    }

    @Override
    public void clear() {
        this.incoming.clear();
        this.outgoing.clear();
        this.toggledOffPlayers.clear();
        this.userSettingsMap.clear();
        this.cooldownsMap.clear();
    }
}
