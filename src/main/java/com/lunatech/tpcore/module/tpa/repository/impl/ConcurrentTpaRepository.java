package com.lunatech.tpcore.module.tpa.repository.impl;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.repository.TpaRepository;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConcurrentTpaRepository implements TpaRepository {

    private final Map<UUID, Map<UUID, TpaRequest>> incoming = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, TpaRequest>> outgoing = new ConcurrentHashMap<>();
    private final Set<UUID> toggledOffPlayers = ConcurrentHashMap.newKeySet();

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
        java.util.List<TpaRequest> all = new java.util.ArrayList<>();
        for (Map<UUID, TpaRequest> map : this.incoming.values()) {
            all.addAll(map.values());
        }
        return Collections.unmodifiableCollection(all);
    }

    @Override
    public void removeRequest(UUID targetId, UUID senderId) {
        Map<UUID, TpaRequest> targetMap = this.incoming.get(targetId);
        if (targetMap != null) {
            targetMap.remove(senderId);
            if (targetMap.isEmpty()) {
                this.incoming.remove(targetId);
            }
        }

        Map<UUID, TpaRequest> senderMap = this.outgoing.get(senderId);
        if (senderMap != null) {
            senderMap.remove(targetId);
            if (senderMap.isEmpty()) {
                this.outgoing.remove(senderId);
            }
        }
    }

    @Override
    public void removeAllRequestsForPlayer(UUID playerId) {
        Map<UUID, TpaRequest> inc = this.incoming.remove(playerId);
        if (inc != null) {
            for (UUID senderId : inc.keySet()) {
                Map<UUID, TpaRequest> out = this.outgoing.get(senderId);
                if (out != null) {
                    out.remove(playerId);
                    if (out.isEmpty()) {
                        this.outgoing.remove(senderId);
                    }
                }
            }
        }

        Map<UUID, TpaRequest> out = this.outgoing.remove(playerId);
        if (out != null) {
            for (UUID targetId : out.keySet()) {
                Map<UUID, TpaRequest> inMap = this.incoming.get(targetId);
                if (inMap != null) {
                    inMap.remove(playerId);
                    if (inMap.isEmpty()) {
                        this.incoming.remove(targetId);
                    }
                }
            }
        }
    }

    @Override
    public boolean isTpaToggledOff(UUID playerId) {
        return this.toggledOffPlayers.contains(playerId);
    }

    @Override
    public void setTpaToggledOff(UUID playerId, boolean toggledOff) {
        if (toggledOff) {
            this.toggledOffPlayers.add(playerId);
        } else {
            this.toggledOffPlayers.remove(playerId);
        }
    }

    @Override
    public void clear() {
        this.incoming.clear();
        this.outgoing.clear();
        this.toggledOffPlayers.clear();
    }
}
