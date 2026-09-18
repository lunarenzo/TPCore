package com.lunatech.tpcore.module.rtp.anvil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    private static final long MCA_SUPERBLOCK_BLINEAR = -0x200812250269L; // 0xFFFFDFF7EDDAFD97L
    private static final long MCA_SUPERBLOCK_LINEAR = 0xc3ff13183cca9d9aL;
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
            Path regionFile = resolveRegionFile(worldFolder, dimensionSubpath, chunkX, chunkZ);
            if (regionFile == null || !Files.isRegularFile(regionFile)) {
                return Verdict.UNKNOWN;
            }

            byte[] regionBytes = AnvilRegionByteCache.getHeader(regionFile);
            if (regionBytes == null || regionBytes.length < 142) {
                return Verdict.UNKNOWN;
            }

            String filename = regionFile.getFileName().toString().toLowerCase(Locale.ROOT);
            int rx = Math.floorMod(chunkX, 32);
            int rz = Math.floorMod(chunkZ, 32);

            if (filename.endsWith(".b_linear")) {
                return probeBLinear(regionBytes, rx, rz);
            } else if (filename.endsWith(".linear")) {
                return probeLinear(regionBytes, rx, rz);
            } else {
                return probeAnvilMca(regionBytes, rx, rz);
            }
        } catch (Exception e) {
            return Verdict.UNKNOWN;
        }
    }

    private static Verdict probeBLinear(byte[] bytes, int rx, int rz) {
        if (bytes.length < 142) {
            return Verdict.UNKNOWN;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long superblock = buf.getLong();
        if (superblock != MCA_SUPERBLOCK_BLINEAR) {
            return Verdict.UNKNOWN;
        }
        byte version = buf.get();
        if (version != 3) {
            return Verdict.UNKNOWN;
        }

        int chunkIndex = rx + 32 * rz;
        int bucketIndex = chunkIndex / 64; // 16 buckets for 1024 chunks

        buf.position(14 + bucketIndex * 8);
        long bucketPos = buf.getLong();

        return bucketPos == 0L ? Verdict.UNKNOWN : Verdict.ACCEPT;
    }

    private static Verdict probeLinear(byte[] bytes, int rx, int rz) {
        if (bytes.length < 154) {
            return Verdict.UNKNOWN;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long superblock = buf.getLong();
        if (superblock != MCA_SUPERBLOCK_LINEAR) {
            return Verdict.UNKNOWN;
        }
        byte version = buf.get();
        if (version != 3) {
            return Verdict.UNKNOWN;
        }

        int chunkIndex = rx + 32 * rz;
        int byteOffset = 26 + (chunkIndex / 8);
        int bitOffset = 7 - (chunkIndex % 8);

        boolean exists = ((bytes[byteOffset] >> bitOffset) & 1) == 1;
        return exists ? Verdict.ACCEPT : Verdict.UNKNOWN;
    }

    private static Verdict probeAnvilMca(byte[] bytes, int rx, int rz) {
        if (bytes.length < 4096) {
            return Verdict.UNKNOWN;
        }
        int headerOffset = 4 * (rx + rz * 32);
        int locationEntry = ((bytes[headerOffset] & 0xFF) << 16)
            | ((bytes[headerOffset + 1] & 0xFF) << 8)
            | (bytes[headerOffset + 2] & 0xFF);

        return locationEntry == 0 ? Verdict.UNKNOWN : Verdict.ACCEPT;
    }

    public static Path resolveRegionFile(Path worldFolder, String dimensionSubpath, int chunkX, int chunkZ) {
        Objects.requireNonNull(worldFolder, "worldFolder cannot be null");
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        String dim = (dimensionSubpath == null) ? "" : dimensionSubpath;
        Path regionDir = dim.isEmpty() ? worldFolder.resolve("region") : worldFolder.resolve(dim).resolve("region");

        String bLinearName = "r." + regionX + "." + regionZ + ".b_linear";
        Path bLinearFile = regionDir.resolve(bLinearName);
        if (Files.isRegularFile(bLinearFile)) {
            return bLinearFile;
        }

        String linearName = "r." + regionX + "." + regionZ + ".linear";
        Path linearFile = regionDir.resolve(linearName);
        if (Files.isRegularFile(linearFile)) {
            return linearFile;
        }

        String mcaName = "r." + regionX + "." + regionZ + ".mca";
        return regionDir.resolve(mcaName);
    }

    public static Path regionFileFor(Path worldFolder, String dimensionSubpath, int chunkX, int chunkZ) {
        return resolveRegionFile(worldFolder, dimensionSubpath, chunkX, chunkZ);
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
