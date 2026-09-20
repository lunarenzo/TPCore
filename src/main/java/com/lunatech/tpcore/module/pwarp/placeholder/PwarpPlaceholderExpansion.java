package com.lunatech.tpcore.module.pwarp.placeholder;

import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

public final class PwarpPlaceholderExpansion extends PlaceholderExpansion {

    private final PwarpService pwarpService;

    public PwarpPlaceholderExpansion(PwarpService pwarpService) {
        this.pwarpService = Objects.requireNonNull(pwarpService, "pwarpService cannot be null");
    }

    @Override
    public @NotNull String getIdentifier() {
        return "tpcore_pwarp";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Lunatech Studio";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("total")) {
            return String.valueOf(this.pwarpService.getPublicWarps().size());
        }

        if (params.equalsIgnoreCase("owned")) {
            if (player == null) {
                return "0";
            }
            return String.valueOf(this.pwarpService.getPlayerWarps(player.getUniqueId()).size());
        }

        if (params.startsWith("visits_")) {
            String name = params.substring("visits_".length());
            return this.pwarpService.getWarp(name).map(w -> String.valueOf(w.visits())).orElse("0");
        }

        if (params.startsWith("rating_")) {
            String name = params.substring("rating_".length());
            return this.pwarpService.getWarp(name).map(w -> String.format("%.1f", w.averageRating())).orElse("0.0");
        }

        if (params.startsWith("price_")) {
            String name = params.substring("price_".length());
            return this.pwarpService.getWarp(name).map(w -> String.format("%.2f", w.price())).orElse("0.00");
        }

        if (params.startsWith("category_")) {
            String name = params.substring("category_".length());
            return this.pwarpService.getWarp(name).map(Pwarp::category).orElse("none");
        }

        if (params.startsWith("owner_")) {
            String name = params.substring("owner_".length());
            return this.pwarpService.getWarp(name).map(Pwarp::ownerName).orElse("unknown");
        }

        return null;
    }
}
