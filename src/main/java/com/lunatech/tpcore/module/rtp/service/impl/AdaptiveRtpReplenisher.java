package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.cache.impl.LockFreeCandidateBuffer;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpConfigManager;
import com.lunatech.tpcore.module.rtp.config.RtpWorldConfig;
import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import com.lunatech.tpcore.module.rtp.repository.RtpCacheRepository;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

public final class AdaptiveRtpReplenisher {

    public enum HealthState {
        RELAXED,
        BUSY,
        STRESSED
    }

    private final Plugin plugin;
    private final RtpConfigManager configManager;
    private final RtpSafetyInspector safetyInspector;
    private final RtpCacheRepository repository;
    private final Logger logger;

    private final Map<UUID, LockFreeCandidateBuffer> bufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDemandMap = new ConcurrentHashMap<>();
    private final Map<UUID, LockFreeCandidateBuffer> restoring = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> generatingMap = new ConcurrentHashMap<>();
    private ScheduledTask task;
    private volatile boolean stopped;
    public void requestAll() {
        if (stopped) return;
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (!stopped) for (World world : Bukkit.getWorlds()) recordDemand(world);
        });
    }

    public AdaptiveRtpReplenisher(
        Plugin plugin,
        RtpConfigManager configManager,
        RtpSafetyInspector safetyInspector,
        RtpCacheRepository repository,
        Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.safetyInspector = Objects.requireNonNull(safetyInspector, "safetyInspector cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    public synchronized void start() {
        if (task != null) return;
        stopped = false;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> {
            try { tickReplenishAll(); }
            catch (RuntimeException failure) { logger.warn("RTP replenishment pass failed", failure); }
        }, 60, 60);
    }

    public synchronized void stop() {
        stopped = true;
        if (task != null) { task.cancel(); task = null; }
        bufferMap.clear(); lastDemandMap.clear(); generatingMap.clear(); restoring.clear();
    }

    public RtpCandidate popCandidate(World world) {
        Objects.requireNonNull(world, "world cannot be null");
        UUID worldUuid = world.getUID();
        this.lastDemandMap.put(worldUuid, System.currentTimeMillis());

        LockFreeCandidateBuffer buffer = this.bufferMap.get(worldUuid);
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }

        long packed = buffer.poll();
        if (packed == LockFreeCandidateBuffer.EMPTY_SENTINEL) {
            return null;
        }

        this.repository.deletePackedLocation(worldUuid, packed);
        return PackedLocation.toCandidate(packed, worldUuid, 0.0f, 0.0f);
    }

    public void recordDemand(World world) {
        if (world != null) {
            if (configManager.getActiveConfig().worldConfigs().containsKey(world.getName().toLowerCase(Locale.ROOT)))
                this.lastDemandMap.put(world.getUID(), System.currentTimeMillis());
        }
    }

    public HealthState sampleHealth() {
        double mspt = Bukkit.getServer().getAverageTickTime();
        RtpConfig config = this.configManager.getActiveConfig();
        if (mspt >= config.maxMsptThreshold()) {
            return HealthState.STRESSED;
        } else if (mspt >= config.maxMsptThreshold() - 10.0) {
            return HealthState.BUSY;
        }
        return HealthState.RELAXED;
    }

    private void tickReplenishAll() {
        RtpConfig config = this.configManager.getActiveConfig();
        if (stopped || !config.enabled()) {
            return;
        }

        HealthState health = sampleHealth();
        if (health == HealthState.STRESSED) {
            return;
        }

        long demandWindowMs = config.demandWindowMinutes() * 60_000L;

        var worlds = Bukkit.getWorlds();
        var ids = worlds.stream().map(World::getUID).collect(Collectors.toSet());
        bufferMap.keySet().retainAll(ids); lastDemandMap.keySet().retainAll(ids); generatingMap.keySet().retainAll(ids); restoring.keySet().retainAll(ids);
        for (World world : worlds) {
            RtpWorldConfig worldConfig = config.worldConfigs().get(world.getName().toLowerCase());
            if (worldConfig == null || !worldConfig.enabled()) {
                continue;
            }

            UUID worldUuid = world.getUID();
            int capacity = Integer.highestOneBit(config.bufferCapacity() - 1) << 1;
            LockFreeCandidateBuffer buffer = bufferMap.get(worldUuid);
            if (buffer == null || buffer.capacity() != capacity) {
                var replacement = new LockFreeCandidateBuffer(config.bufferCapacity());
                bufferMap.put(worldUuid, replacement);
                restoring.put(worldUuid, replacement);
                repository.loadPackedLocations(worldUuid).whenComplete((saved, error) -> {
                    try {
                        if (error != null) { logger.warn("RTP cache restore failed for {}", worldUuid, error); return; }
                        if (!stopped && bufferMap.get(worldUuid) == replacement)
                            for (long packed : saved) if (!replacement.offer(packed)) break;
                    } finally { restoring.remove(worldUuid, replacement); }
                });
                buffer = replacement;
            }
            if (restoring.containsKey(worldUuid)) continue;

            Long lastDemand = this.lastDemandMap.get(worldUuid);
            boolean isDemandFresh = lastDemand != null && (System.currentTimeMillis() - lastDemand) <= demandWindowMs;
            int minPreWarmed = Math.max(2, Math.min(5, config.bufferCapacity()));
            int targetCapacity = isDemandFresh ? config.bufferCapacity() : minPreWarmed;
            int needed = targetCapacity - buffer.size();
            if (needed <= 0) {
                continue;
            }

            int batchCount = Math.min(needed, 4);
            for (int b = 0; b < batchCount; b++) {
                replenishSingle(world, worldConfig, buffer, 0);
            }
        }
    }

    private void replenishSingle(World world, RtpWorldConfig worldConfig, LockFreeCandidateBuffer buffer, int attempt) {
        if (stopped || attempt >= worldConfig.maxAttempts() || buffer.size() >= buffer.capacity()) {
            return;
        }

        double[] point = DefaultRtpService.randomPoint(worldConfig);
        int candidateX = (int) Math.floor(point[0]), candidateZ = (int) Math.floor(point[1]);

        UUID worldUuid = world.getUID();
        AtomicBoolean genLock = this.generatingMap.computeIfAbsent(worldUuid, k -> new AtomicBoolean(false));

        if (!genLock.compareAndSet(false, true)) {
            return;
        }

        this.safetyInspector.inspectCandidate(world, worldConfig, candidateX, candidateZ, configManager.getActiveConfig().runtime().generateChunks())
            .whenComplete((candidate, ex) -> {
                genLock.set(false);
                if (stopped || bufferMap.get(worldUuid) != buffer) return;
                if (ex == null && candidate != null) {
                    long packed = PackedLocation.fromCandidate(candidate);
                    if (buffer.offer(packed)) {

                        this.repository.savePackedLocation(worldUuid, packed);
                    }
                } else if (buffer.size() < buffer.capacity()) {
                    replenishSingle(world, worldConfig, buffer, attempt + 1);
                }
            });
    }
}
