package com.lunatech.tpcore.module.back.cache;

import com.lunatech.tpcore.module.back.model.BackLocation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface BackCache {

    void populate(Map<UUID, List<BackLocation>> data);

    void pushLocation(UUID playerUuid, BackLocation location, int maxHistoryDepth);

    Optional<BackLocation> peekLastLocation(UUID playerUuid);

    Optional<BackLocation> popLastLocation(UUID playerUuid);

    Optional<BackLocation> peekLastDeathLocation(UUID playerUuid);

    Optional<BackLocation> popLastDeathLocation(UUID playerUuid);

    boolean removeLocation(UUID playerUuid, BackLocation location);

    boolean removeLocationAtIndex(UUID playerUuid, int index);

    List<BackLocation> getHistory(UUID playerUuid);

    void clearPlayerHistory(UUID playerUuid);

    void clear();
}
