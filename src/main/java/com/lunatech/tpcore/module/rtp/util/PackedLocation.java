package com.lunatech.tpcore.module.rtp.util;

import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import java.util.UUID;

public final class PackedLocation {

    private static final long OFFSET_X = 30_000_000L;
    private static final long OFFSET_Z = 30_000_000L;
    private static final long Y_MASK = 0xFFFL;

    private PackedLocation() {}

    public static long pack(int x, int y, int z) {
        long px = (x + OFFSET_X) & 0x3FFFFFFL;
        long pz = (z + OFFSET_Z) & 0x3FFFFFFL;
        long py = y & Y_MASK;
        return (px << 38) | (pz << 12) | py;
    }

    public static int unpackX(long packed) {
        long rawX = (packed >>> 38) & 0x3FFFFFFL;
        return (int) (rawX - OFFSET_X);
    }

    public static int unpackZ(long packed) {
        long rawZ = (packed >>> 12) & 0x3FFFFFFL;
        return (int) (rawZ - OFFSET_Z);
    }

    public static int unpackY(long packed) {
        short rawY = (short) (packed & Y_MASK);
        if ((rawY & 0x800) != 0) {
            return rawY | ~0xFFF;
        }
        return rawY;
    }

    public static RtpCandidate toCandidate(long packed, UUID worldUuid, float yaw, float pitch) {
        return new RtpCandidate(
            unpackX(packed) + 0.5,
            unpackY(packed),
            unpackZ(packed) + 0.5,
            yaw,
            pitch,
            worldUuid,
            System.currentTimeMillis()
        );
    }

    public static long fromCandidate(RtpCandidate candidate) {
        return pack((int) Math.floor(candidate.x()), (int) Math.floor(candidate.y()), (int) Math.floor(candidate.z()));
    }
}
