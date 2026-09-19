package com.lunatech.tpcore.module.rtp.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
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
    private static final MethodHandle GET_KEY_STRING_HANDLE;
    private static final MethodHandle ENUM_NAME_HANDLE;

    static {
        MethodHandle getBiome = null;
        MethodHandle getKey = null;
        MethodHandle getKeyString = null;
        MethodHandle enumName = null;

        try {
            Method handlesLookupMethod = Block.class.getMethod("getBiome");
            getBiome = MethodHandles.lookup().unreflect(handlesLookupMethod);

            Class<?> keyedClass = Class.forName("org.bukkit.Keyed");
            Method getKeyMethod = keyedClass.getMethod("getKey");
            getKey = MethodHandles.lookup().unreflect(getKeyMethod);

            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey");
            Method getKeyStringMethod = namespacedKeyClass.getMethod("getKey");
            getKeyString = MethodHandles.lookup().unreflect(getKeyStringMethod);
        } catch (Throwable ignored) {
        }

        try {
            Method nameMethod = Enum.class.getMethod("name");
            enumName = MethodHandles.lookup().unreflect(nameMethod);
        } catch (Throwable ignored) {
        }

        GET_BIOME_HANDLE = getBiome;
        GET_KEY_HANDLE = getKey;
        GET_KEY_STRING_HANDLE = getKeyString;
        ENUM_NAME_HANDLE = enumName;
    }

    private BiomeResolver() {}

    /**
     * Resolves the upper-case key name of the biome for the target block.
     * Guaranteed cross-version safe on Paper 1.20.6, 1.21.x, and 26.x.
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
            if (GET_KEY_HANDLE != null && GET_KEY_STRING_HANDLE != null) {
                try {
                    Object keyObj = GET_KEY_HANDLE.invoke(biomeObj);
                    if (keyObj != null) {
                        Object keyStr = GET_KEY_STRING_HANDLE.invoke(keyObj);
                        if (keyStr instanceof String str && !str.isEmpty()) {
                            return str.toUpperCase();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // Fallback to Enum name (1.20.6 / legacy Bukkit)
            if (ENUM_NAME_HANDLE != null) {
                try {
                    Object nameObj = ENUM_NAME_HANDLE.invoke(biomeObj);
                    if (nameObj instanceof String nameStr && !nameStr.isEmpty()) {
                        return nameStr.toUpperCase();
                    }
                } catch (Throwable ignored) {
                }
            }

            return biomeObj.toString().toUpperCase();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
