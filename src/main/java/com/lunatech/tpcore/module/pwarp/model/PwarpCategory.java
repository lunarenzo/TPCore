package com.lunatech.tpcore.module.pwarp.model;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import java.util.Locale;

/**
 * Configurable category model for grouping player warps (e.g. Shops, Farms, Arenas).
 */
@ConfigSerializable
public record PwarpCategory(
    String key,
    String displayName,
    String iconMaterial,
    String description,
    int slot
) {
    public PwarpCategory {
        key = (key != null) ? key.toLowerCase(Locale.ROOT) : "general";
        displayName = (displayName != null) ? displayName : "General";
        iconMaterial = (iconMaterial != null && !iconMaterial.isBlank()) ? iconMaterial : "CHEST";
        description = (description != null) ? description : "";
    }
}
