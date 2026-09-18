package com.lunatech.tpcore.module.rtp.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class AnvilPrefilter {

    public enum Verdict {
        ACCEPT,
        REJECT,
        UNKNOWN
    }

    private static final int BITS_PER_ENTRY = 9;

    private AnvilPrefilter() {}

    public static Verdict probeSync(
        Path worldFolder,
        String dimensionSubpath,
        int chunkX,
        int chunkZ,
        Set<String> rawUnsafeBlocks
    ) {
        if (worldFolder == null) {
            return Verdict.UNKNOWN;
        }

        try {
            Path regionFile = regionFileFor(worldFolder, dimensionSubpath, chunkX, chunkZ);
            if (!Files.isRegularFile(regionFile)) {
                return Verdict.UNKNOWN;
            }

            byte[] regionBytes = AnvilRegionByteCache.getHeader(regionFile);
            if (regionBytes == null || regionBytes.length < 4096) {
                return Verdict.UNKNOWN;
            }

            int rx = Math.floorMod(chunkX, 32);
            int rz = Math.floorMod(chunkZ, 32);
            int headerOffset = 4 * (rx + rz * 32);

            int locationEntry = ((regionBytes[headerOffset] & 0xFF) << 16)
                | ((regionBytes[headerOffset + 1] & 0xFF) << 8)
                | (regionBytes[headerOffset + 2] & 0xFF);

            if (locationEntry == 0) {
                return Verdict.UNKNOWN;
            }

            return Verdict.ACCEPT;
        } catch (Exception e) {
            return Verdict.UNKNOWN;
        }
    }

    public static Path regionFileFor(Path worldFolder, String dimensionSubpath, int chunkX, int chunkZ) {
        Objects.requireNonNull(worldFolder, "worldFolder cannot be null");
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        String filename = "r." + regionX + "." + regionZ + ".mca";
        String dim = (dimensionSubpath == null) ? "" : dimensionSubpath;
        if (dim.isEmpty()) {
            return worldFolder.resolve("region").resolve(filename);
        }
        return worldFolder.resolve(dim).resolve("region").resolve(filename);
    }

    public static int readHeightmapEntry(long[] packed, int x, int z) {
        if (packed == null || packed.length == 0) return -1;
        int entriesPerLong = 64 / BITS_PER_ENTRY;
        int columnIndex = z * 16 + x;
        int longIndex = columnIndex / entriesPerLong;
        if (longIndex >= packed.length) return -1;
        int bitOffset = (columnIndex % entriesPerLong) * BITS_PER_ENTRY;
        long mask = (1L << BITS_PER_ENTRY) - 1L;
        return (int) ((packed[longIndex] >>> bitOffset) & mask);
    }

    public static String normalizeBlockId(String rawId) {
        if (rawId == null) return null;
        int colon = rawId.indexOf(':');
        String stripped = (colon >= 0) ? rawId.substring(colon + 1) : rawId;
        return stripped.toUpperCase(Locale.ROOT);
    }
}
