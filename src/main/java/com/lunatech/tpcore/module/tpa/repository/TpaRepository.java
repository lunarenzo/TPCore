package com.lunatech.tpcore.module.tpa.repository;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaUserSettings;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TpaRepository {

    void addRequest(TpaRequest request);

    Optional<TpaRequest> getRequest(UUID targetId, UUID senderId);

    Collection<TpaRequest> getIncomingRequests(UUID targetId);

    Collection<TpaRequest> getOutgoingRequests(UUID senderId);

    Collection<TpaRequest> getAllRequests();

    void removeRequest(UUID targetId, UUID senderId);

    void removeAllRequestsForPlayer(UUID playerId);

    boolean isTpaToggledOff(UUID playerId);

    void setTpaToggledOff(UUID playerId, boolean toggledOff);

    TpaUserSettings getUserSettings(UUID playerId);

    void setUserSettings(UUID playerId, TpaUserSettings settings);

    boolean isPlayerBlocked(UUID targetId, UUID senderId);

    void setPlayerBlocked(UUID playerId, UUID targetId, boolean blocked);

    long getCooldownEnd(UUID senderId);

    void setCooldownEnd(UUID senderId, long endTimestamp);

    void clear();
}
