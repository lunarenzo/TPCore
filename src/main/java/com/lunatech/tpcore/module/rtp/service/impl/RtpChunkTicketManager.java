package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional, bounded post-dispatch retention. Every ticket is changed on its own region. */
public final class RtpChunkTicketManager {
    private record Key(UUID world, int x, int z) {}
    private final JavaPlugin plugin;
    private static final class Lease { int count; }
    private final Map<Key, Lease> leases = new HashMap<>();
    private int outstanding;
    private boolean closed;
    public RtpChunkTicketManager(JavaPlugin plugin) { this.plugin = plugin; }

    public void addCandidateTickets(World world, long packed) {
        int x = PackedLocation.unpackX(packed) >> 4, z = PackedLocation.unpackZ(packed) >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) addChunkTicket(world, x + dx, z + dz);
    }
    public void addChunkTicket(World world, int x, int z) {
        var key = new Key(world.getUID(), x, z);
        Lease lease;
        synchronized (leases) {
            if (closed || outstanding >= 1024) return;
            outstanding++;
            lease = leases.computeIfAbsent(key, ignored -> new Lease());
            lease.count++;
        }
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, x, z, () -> {
                synchronized (leases) {
                    if (closed || leases.get(key) != lease) return;
                    try {
                        if (!world.isChunkLoaded(x, z)) { release(world, key, lease); return; }
                        world.addPluginChunkTicket(x, z, plugin);
                        Bukkit.getRegionScheduler().runDelayed(plugin, world, x, z, ignored -> release(world, key, lease), 100);
                    } catch (RuntimeException failure) { release(world, key, lease); }
                }
            });
        } catch (RuntimeException failure) {
            synchronized (leases) { if (leases.get(key) == lease) { outstanding--; if (--lease.count == 0) leases.remove(key); } }
        }
    }
    private void release(World world, Key key, Lease lease) {
        synchronized (leases) {
            if (leases.get(key) != lease) return;
            outstanding--;
            if (--lease.count == 0) {
                leases.remove(key);
                world.removePluginChunkTicket(key.x(), key.z(), plugin);
            }
        }
    }
    public void close() { synchronized (leases) { closed = true; } clear(); }
    public void clear() {
        Key[] keys;
        synchronized (leases) { keys = leases.keySet().toArray(Key[]::new); leases.clear(); outstanding = 0; }
        // Paper/Folia remove plugin tickets during plugin disable; disabled plugins cannot schedule tasks.
        if (!plugin.isEnabled()) return;
        for (var key : keys) {
            var world = Bukkit.getWorld(key.world());
            if (world == null) continue;
            try { Bukkit.getRegionScheduler().execute(plugin, world, key.x(), key.z(),
                () -> { synchronized (leases) {
                    if (!leases.containsKey(key)) world.removePluginChunkTicket(key.x(), key.z(), plugin);
                } }); }
            catch (RuntimeException failure) { plugin.getSLF4JLogger().warn("RTP ticket cleanup could not be scheduled for {}", key, failure); }
        }
    }
}
