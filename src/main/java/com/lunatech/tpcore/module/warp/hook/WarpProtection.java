package com.lunatech.tpcore.module.warp.hook;

import com.lunatech.tpcore.config.model.WarpPolicyConfig;
import com.lunatech.tpcore.module.warp.hook.impl.GriefPreventionWarpHook;
import com.lunatech.tpcore.module.warp.hook.impl.WorldGuardWarpHook;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/** Fail closed when an explicitly enabled provider cannot be checked safely. */
public final class WarpProtection {
    private final List<WarpProtectionHook> hooks;
    private final boolean available;
    private final Logger logger;
    private final AtomicBoolean reportedFailure = new AtomicBoolean();

    public WarpProtection(WarpPolicyConfig settings, Logger logger) {
        this.logger = logger;
        List<WarpProtectionHook> resolved = new ArrayList<>(2);
        boolean valid = true;
        try {
            if (settings.worldguardEnabled()) {
                Plugin provider = Bukkit.getPluginManager().getPlugin("WorldGuard");
                if (provider == null || !provider.isEnabled()) valid = false;
                else resolved.add(new WorldGuardWarpHook(provider));
            }
            if (settings.griefpreventionEnabled()) {
                Plugin provider = Bukkit.getPluginManager().getPlugin("GriefPrevention");
                if (provider == null || !provider.isEnabled()) valid = false;
                else resolved.add(new GriefPreventionWarpHook(provider));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            logger.error("Enabled warp protection integration is incompatible; operations will be denied", failure);
            valid = false;
        }
        available = valid;
        hooks = List.copyOf(resolved);
        if (!valid) logger.error("Enabled warp protection integration is unavailable; check installed providers and reload warp");
    }

    public boolean allows(Player player, Location source, Location destination, boolean creation) {
        if (!available) return false;
        if (hooks.isEmpty()) return true;
        // No claim that arbitrary providers/forks are safe across Folia regions.
        if (!"Paper".equals(Bukkit.getName()) || !Bukkit.isPrimaryThread()) {
            if (reportedFailure.compareAndSet(false, true)) logger.error("Enabled warp protection hooks require Paper's main thread; operations are denied on this platform/context");
            return false;
        }
        try {
            for (WarpProtectionHook hook : hooks) {
                if (!(creation ? hook.allowsCreation(player, destination) : hook.allowsTravel(player, source, destination))) return false;
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            if (reportedFailure.compareAndSet(false, true)) logger.error("Warp protection query failed; denying operations until it can be checked successfully", failure);
            return false;
        }
    }
}
