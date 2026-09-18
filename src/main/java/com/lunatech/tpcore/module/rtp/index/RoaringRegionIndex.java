package com.lunatech.tpcore.module.rtp.index;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

public final class RoaringRegionIndex {

    private static final class AtomicRegionBitmap {
        private final AtomicLongArray words = new AtomicLongArray(16);

        public void set(int bitIndex) {
            int wordIndex = bitIndex >>> 6;
            long mask = 1L << (bitIndex & 63);
            while (true) {
                long oldVal = words.get(wordIndex);
                long newVal = oldVal | mask;
                if (oldVal == newVal || words.compareAndSet(wordIndex, oldVal, newVal)) {
                    return;
                }
            }
        }

        public void clear(int bitIndex) {
            int wordIndex = bitIndex >>> 6;
            long mask = 1L << (bitIndex & 63);
            while (true) {
                long oldVal = words.get(wordIndex);
                long newVal = oldVal & ~mask;
                if (oldVal == newVal || words.compareAndSet(wordIndex, oldVal, newVal)) {
                    return;
                }
            }
        }

        public boolean get(int bitIndex) {
            int wordIndex = bitIndex >>> 6;
            long mask = 1L << (bitIndex & 63);
            return (words.get(wordIndex) & mask) != 0;
        }

        public boolean isEmpty() {
            for (int i = 0; i < 16; i++) {
                if (words.get(i) != 0L) return false;
            }
            return true;
        }

        public int nextSetBit(int fromIndex) {
            if (fromIndex < 0 || fromIndex >= 1024) {
                fromIndex = 0;
            }
            int wordIndex = fromIndex >>> 6;
            long word = words.get(wordIndex) & (-1L << (fromIndex & 63));

            while (true) {
                if (word != 0L) {
                    return (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                }
                if (++wordIndex >= 16) {
                    return -1;
                }
                word = words.get(wordIndex);
            }
        }
    }

    private final Map<Long, AtomicRegionBitmap> regionBitmaps = new ConcurrentHashMap<>();

    public void markSafe(int chunkX, int chunkZ) {
        long regionKey = getRegionKey(chunkX >> 5, chunkZ >> 5);
        int localIndex = getLocalChunkIndex(chunkX, chunkZ);
        regionBitmaps.computeIfAbsent(regionKey, k -> new AtomicRegionBitmap()).set(localIndex);
    }

    public void markUnsafe(int chunkX, int chunkZ) {
        long regionKey = getRegionKey(chunkX >> 5, chunkZ >> 5);
        AtomicRegionBitmap bitmap = regionBitmaps.get(regionKey);
        if (bitmap != null) {
            int localIndex = getLocalChunkIndex(chunkX, chunkZ);
            bitmap.clear(localIndex);
        }
    }

    public boolean isSafe(int chunkX, int chunkZ) {
        long regionKey = getRegionKey(chunkX >> 5, chunkZ >> 5);
        AtomicRegionBitmap bitmap = regionBitmaps.get(regionKey);
        if (bitmap == null) {
            return false;
        }
        int localIndex = getLocalChunkIndex(chunkX, chunkZ);
        return bitmap.get(localIndex);
    }

    public int findNextSafeChunkIndex(int regionX, int regionZ, int startOffset) {
        long regionKey = getRegionKey(regionX, regionZ);
        AtomicRegionBitmap bitmap = regionBitmaps.get(regionKey);
        if (bitmap == null || bitmap.isEmpty()) {
            return -1;
        }
        int next = bitmap.nextSetBit(Math.floorMod(startOffset, 1024));
        if (next < 0) {
            next = bitmap.nextSetBit(0);
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
