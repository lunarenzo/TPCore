package com.lunatech.tpcore.module.rtp.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class AnvilRegionByteCache {

    private static final int MAX_ENTRIES = 16;
    private static final ReentrantLock LOCK = new ReentrantLock();

    private static final Map<Path, byte[]> CACHE = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private AnvilRegionByteCache() {}

    public static byte[] get(Path regionFile) {
        Objects.requireNonNull(regionFile, "regionFile cannot be null");
        LOCK.lock();
        try {
            byte[] cached = CACHE.get(regionFile);
            if (cached != null) {
                return cached;
            }
        } finally {
            LOCK.unlock();
        }

        if (!Files.isRegularFile(regionFile)) {
            return null;
        }

        try {
            byte[] readBytes = Files.readAllBytes(regionFile);
            LOCK.lock();
            try {
                CACHE.put(regionFile, readBytes);
            } finally {
                LOCK.unlock();
            }
            return readBytes;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear() {
        LOCK.lock();
        try {
            CACHE.clear();
        } finally {
            LOCK.unlock();
        }
    }
}
