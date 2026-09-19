package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.module.rtp.cache.impl.LockFreeCandidateBuffer;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpConfigManager;
import com.lunatech.tpcore.module.rtp.config.RtpWorldConfig;
import com.lunatech.tpcore.module.rtp.index.RoaringRegionIndex;
import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import com.lunatech.tpcore.module.rtp.repository.RtpCacheRepository;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
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
    private final RoaringRegionIndex spatialIndex;
    private final Logger logger;

    private final Map<UUID, LockFreeCandidateBuffer> bufferMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDemandMap = new ConcurrentHashMap<>();
    private ScheduledExecutorService virtualScheduler;

    public AdaptiveRtpReplenisher(
        Plugin plugin,
        RtpConfigManager configManager,
        RtpSafetyInspector safetyInspector,
        RtpCacheRepository repository,
        RoaringRegionIndex spatialIndex,
        Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager cannot be null");
        this.safetyInspector = Objects.requireNonNull(safetyInspector, "safetyInspector cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.spatialIndex = Objects.requireNonNull(spatialIndex, "spatialIndex cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
    }

    public synchronized void start() {
        if (this.virtualScheduler != null && !this.virtualScheduler.isShutdown()) {
            return;
        }

        this.virtualScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        this.virtualScheduler.scheduleAtFixedRate(this::tickReplenishAll, 5, 5, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (this.virtualScheduler != null && !this.virtualScheduler.isShutdown()) {
            this.virtualScheduler.shutdown();
            try {
                if (!this.virtualScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    this.virtualScheduler.shutdownNow();
                }
            } catch (Exception e) {
                this.logger.error("Error stopping AdaptiveRtpReplenisher scheduler", e);
            }
        }
        this.bufferMap.values().forEach(LockFreeCandidateBuffer::clear);
        this.bufferMap.clear();
        this.lastDemandMap.clear();
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
        if (!config.enabled()) {
            return;
        }

        HealthState health = sampleHealth();
        if (health == HealthState.STRESSED) {
            return;
        }

        boolean serverEmpty = Bukkit.getOnlinePlayers().isEmpty();
        long demandWindowMs = config.demandWindowMinutes() * 60_000L;

        for (World world : Bukkit.getWorlds()) {
            RtpWorldConfig worldConfig = config.worldConfigs().get(world.getName().toLowerCase());
            if (worldConfig == null || !worldConfig.enabled()) {
                continue;
            }

            UUID worldUuid = world.getUID();
            LockFreeCandidateBuffer buffer = this.bufferMap.computeIfAbsent(
                worldUuid,
                k -> new LockFreeCandidateBuffer(Math.min(32, Math.max(1, config.bufferCapacity())))
            );

            if (serverEmpty && !buffer.isEmpty()) {
                continue;
            }

            Long lastDemand = this.lastDemandMap.get(worldUuid);
            boolean isDemandFresh = lastDemand != null && (System.currentTimeMillis() - lastDemand) <= demandWindowMs;
            if (!isDemandFresh && !buffer.isEmpty()) {
                continue;
            }

            int targetCapacity = isDemandFresh ? config.bufferCapacity() : 1;
            if (buffer.size() >= targetCapacity) {
                continue;
            }

            int needed = (health == HealthState.BUSY) ? 1 : Math.min(2, targetCapacity - buffer.size());
            for (int i = 0; i < needed; i++) {
                replenishSingle(world, worldConfig, buffer);
            }
        }
    }

    private void replenishSingle(World world, RtpWorldConfig worldConfig, LockFreeCandidateBuffer buffer) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int candidateX;
        int candidateZ;

        if (worldConfig.shapeEnum() == RtpWorldConfig.Shape.SQUARE) {
            int span = Math.max(1, worldConfig.maxRadius());
            int inner = Math.max(0, Math.min(worldConfig.minRadius(), span - 1));
            int dx;
            int dz;
            int maxAttempts = 10;
            do {
                dx = rng.nextInt(-span, span + 1);
                dz = rng.nextInt(-span, span + 1);
            } while (Math.max(Math.abs(dx), Math.abs(dz)) < inner && --maxAttempts > 0);
            candidateX = worldConfig.centerX() + dx;
            candidateZ = worldConfig.centerZ() + dz;
        } else {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(rng.nextDouble() * (Math.pow(worldConfig.maxRadius(), 2) - Math.pow(worldConfig.minRadius(), 2)) + Math.pow(worldConfig.minRadius(), 2));
            candidateX = worldConfig.centerX() + (int) (r * Math.cos(angle));
            candidateZ = worldConfig.centerZ() + (int) (r * Math.sin(angle));
        }

        this.safetyInspector.inspectCandidate(world, worldConfig, candidateX, candidateZ).thenAccept(candidate -> {
            if (candidate != null) {
                long packed = PackedLocation.fromCandidate(candidate);
                if (buffer.offer(packed)) {
                    this.spatialIndex.markSafe((int) candidate.x() >> 4, (int) candidate.z() >> 4);
                    this.repository.savePackedLocation(world.getUID(), packed);
                }
            }
        });
    }
}
