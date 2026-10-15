package com.lunatech.tpcore.module.warp.listener;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.service.WarpSignService;
import java.util.function.Supplier;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class WarpSignListener implements Listener {
    private final WarpSignService signs;
    private final Supplier<WarpConfig> config;
    public WarpSignListener(WarpSignService signs, Supplier<WarpConfig> config) { this.signs = signs; this.config = config; }

    // Interact has separate block/item denials. The service respects the block denial instead of deprecated isCancelled().
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void interact(PlayerInteractEvent event) { signs.interact(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void edit(SignChangeEvent event) {
        if (!config.get().enabled() || !config.get().signs().enabled()) return;
        if (event.getBlock().getState() instanceof Sign sign && signs.managed(sign, event.getSide())) {
            event.setCancelled(true); signs.message(event.getPlayer(), "edit-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakSign(BlockBreakEvent event) {
        WarpConfig settings = config.get();
        if (!settings.enabled() || !settings.signs().enabled() || !settings.signs().protectBreaks()) return;
        Sign sign = signs.sign(event.getBlock());
        if (sign != null && signs.managed(sign)
            && !signs.allowed(event.getPlayer(), Permissions.WARP_SIGN_BREAK)) {
            event.setCancelled(true); signs.message(event.getPlayer(), "no-permission");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { signs.forget(event.getPlayer().getUniqueId()); }
}
