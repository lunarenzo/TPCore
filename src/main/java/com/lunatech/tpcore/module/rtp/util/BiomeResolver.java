package com.lunatech.tpcore.module.rtp.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.block.Block;

/**
 * Single-JAR cross-version biome resolver bridging Paper 1.20.6 (where Biome was an Enum)
 * and Paper 1.21.x / 26.x (where Biome is a Keyed interface or registry object).
 *
 * Uses cached MethodHandles for zero-allocation, low-latency execution on hot tick paths.
 */
public final class BiomeResolver {

    private static final MethodHandle GET_BIOME_HANDLE;
    private static final MethodHandle GET_KEY_HANDLE;
    private static final MethodHandle ENUM_NAME_HANDLE;

    static {
        MethodHandle getBiome = null;
        MethodHandle getKey = null;
        MethodHandle enumName = null;

        try {
            Method handlesLookupMethod = Block.class.getMethod("getBiome");
            getBiome = MethodHandles.lookup().unreflect(handlesLookupMethod);

            Class<?> keyedClass = Class.forName("org.bukkit.Keyed");
            Method getKeyMethod = keyedClass.getMethod("getKey");
            getKey = MethodHandles.lookup().unreflect(getKeyMethod);

        } catch (Throwable ignored) {
        }

        try {
            Method nameMethod = Enum.class.getMethod("name");
            enumName = MethodHandles.lookup().unreflect(nameMethod);
        } catch (Throwable ignored) {
        }

        GET_BIOME_HANDLE = getBiome;
        GET_KEY_HANDLE = getKey;
        ENUM_NAME_HANDLE = enumName;
    }

    private BiomeResolver() {}

    static String normalizeKey(String raw) {
        if (raw == null) return "";
        String key = raw.toUpperCase(Locale.ROOT);
        return key.startsWith("MINECRAFT:") ? key.substring(10) : key;
    }

    /**
     * Resolves the upper-case key name of the biome for the target block.
     * Uses runtime capabilities; unavailable keys are rejected by configured filters.
     *
     * @param block target block
     * @return uppercase biome key (e.g., "PLAINS", "OCEAN") or empty string on failure
     */
    public static String getBiomeKey(Block block) {
        if (block == null || GET_BIOME_HANDLE == null) {
            return "";
        }

        try {
            Object biomeObj = GET_BIOME_HANDLE.invoke(block);
            if (biomeObj == null) {
                return "";
            }

            // Try Keyed (1.21+ / modern Paper)
            if (GET_KEY_HANDLE != null) {
                try {
                    Object keyObj = GET_KEY_HANDLE.invoke(biomeObj);
                    if (keyObj != null) {
                        String key = normalizeKey(keyObj.toString());
                        if (!key.isEmpty()) return key;
                    }
                } catch (Throwable ignored) {
                }
            }

            // Fallback to Enum name (1.20.6 / legacy Bukkit)
            if (ENUM_NAME_HANDLE != null) {
                try {
                    Object nameObj = ENUM_NAME_HANDLE.invoke(biomeObj);
                    if (nameObj instanceof String nameStr && !nameStr.isEmpty()) {
                        return normalizeKey(nameStr);
                    }
                } catch (Throwable ignored) {
                }
            }

            return normalizeKey(biomeObj.toString());
        } catch (Throwable ignored) {
            return "";
        }
    }
}
