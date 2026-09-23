package com.lunatech.tpcore.module.tpa.repository;

import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaUserSettings;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public interface TpaRepository {

    void addRequest(TpaRequest request);

    Optional<TpaRequest> getRequest(UUID targetId, UUID senderId);

    Collection<TpaRequest> getIncomingRequests(UUID targetId);

    Collection<TpaRequest> getOutgoingRequests(UUID senderId);

    Collection<TpaRequest> getAllRequests();

    void forEachRequest(Consumer<TpaRequest> action);

    boolean removeRequest(UUID targetId, UUID senderId);

    void removeAllRequestsForPlayer(UUID playerId);

    boolean isTpaToggledOff(UUID playerId);

    void setTpaToggledOff(UUID playerId, boolean toggledOff);

    boolean isAutoAcceptEnabled(UUID playerId);

    void setAutoAcceptEnabled(UUID playerId, boolean autoAccept);

    TpaUserSettings getUserSettings(UUID playerId);

    void setUserSettings(UUID playerId, TpaUserSettings settings);

    boolean isPlayerBlocked(UUID targetId, UUID senderId);

    void setPlayerBlocked(UUID playerId, UUID targetId, boolean blocked);

    long getCooldownEnd(UUID senderId);

    void setCooldownEnd(UUID senderId, long endTimestamp);

    void clearExpiredCooldowns();

    void clear();
}
