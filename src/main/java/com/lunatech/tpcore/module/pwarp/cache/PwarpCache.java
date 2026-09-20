package com.lunatech.tpcore.module.pwarp.cache;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PwarpCache {

    void put(Pwarp pwarp);

    void remove(String name);

    Optional<Pwarp> getByName(String name);

    List<Pwarp> getByOwner(UUID ownerUuid);

    List<Pwarp> getAllPublic();

    List<Pwarp> getAll();

    int countByOwner(UUID ownerUuid);

    int size();

    void clear();
}
