package com.lunatech.tpcore.module.rtp.index;

import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RoaringRegionIndex {

    private final Map<Long, BitSet> regionBitmaps = new ConcurrentHashMap<>();

    public void markSafe(int chunkX, int chunkZ) {
        long regionKey = getRegionKey(chunkX >> 5, chunkZ >> 5);
        int localIndex = getLocalChunkIndex(chunkX, chunkZ);
        regionBitmaps.computeIfAbsent(regionKey, k -> new BitSet(1024)).set(localIndex);
    }

    public void markUnsafe(int chunkX, int chunkZ) {
        long regionKey = getRegionKey(chunkX >> 5, chunkZ >> 5);
        BitSet bitSet = regionBitmaps.get(regionKey);
        if (bitSet != null) {
            int localIndex = getLocalChunkIndex(chunkX, chunkZ);
            bitSet.clear(localIndex);
        }
    }

    public boolean isSafe(int chunkX, int chunkZ) {
        long regionKey = getRegionKey(chunkX >> 5, chunkZ >> 5);
        BitSet bitSet = regionBitmaps.get(regionKey);
        if (bitSet == null) {
            return false;
        }
        int localIndex = getLocalChunkIndex(chunkX, chunkZ);
        return bitSet.get(localIndex);
    }

    public int findNextSafeChunkIndex(int regionX, int regionZ, int startOffset) {
        long regionKey = getRegionKey(regionX, regionZ);
        BitSet bitSet = regionBitmaps.get(regionKey);
        if (bitSet == null || bitSet.isEmpty()) {
            return -1;
        }
        int next = bitSet.nextSetBit(Math.floorMod(startOffset, 1024));
        if (next < 0) {
            next = bitSet.nextSetBit(0);
        }
        return next;
    }

    public void clear() {
        regionBitmaps.clear();
    }

    private static long getRegionKey(int regionX, int regionZ) {
        return (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
    }

    private static int getLocalChunkIndex(int chunkX, int chunkZ) {
        int rx = Math.floorMod(chunkX, 32);
        int rz = Math.floorMod(chunkZ, 32);
        return rx + (rz * 32);
    }
}
