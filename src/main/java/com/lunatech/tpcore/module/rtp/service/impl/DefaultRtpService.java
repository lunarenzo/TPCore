package com.lunatech.tpcore.module.rtp.service.impl;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.config.RtpWorldConfig;
import com.lunatech.tpcore.module.rtp.model.RtpCandidate;
import com.lunatech.tpcore.module.rtp.service.RtpService;
import com.lunatech.tpcore.module.rtp.util.PackedLocation;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultRtpService implements RtpService {
    private final JavaPlugin plugin;
    private final Supplier<RtpConfig> configSupplier;
    private final AdaptiveRtpReplenisher replenisher;
    private final RtpSafetyInspector safetyInspector;
    private final RtpChunkTicketManager ticketManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Long> cooldownMap;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private static final class Request {
        final UUID player, world, source;
        final String worldName;
        final WeakReference<Player> handle;
        final RtpWorldConfig policy;
        final boolean otherWorld;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        final CompletableFuture<Void> deadline = new CompletableFuture<>();
        volatile ScheduledTask warmup;
        volatile boolean dispatched, cancelled;
        Request(Player player, World world, RtpWorldConfig policy, boolean otherWorld) {
            this.player = player.getUniqueId(); this.source = player.getWorld().getUID();
            this.world = world.getUID(); this.worldName = world.getName(); this.policy = policy;
            this.handle = new WeakReference<>(player); this.otherWorld = otherWorld;
        }
    }

    public DefaultRtpService(JavaPlugin plugin, Supplier<RtpConfig> configSupplier,
        AdaptiveRtpReplenisher replenisher, RtpSafetyInspector safetyInspector, RtpChunkTicketManager ticketManager, Map<UUID, Long> cooldowns) {
        this.cooldownMap = cooldowns;
        this.plugin = plugin; this.configSupplier = configSupplier; this.replenisher = replenisher;
        this.safetyInspector = safetyInspector; this.ticketManager = ticketManager;
    }

    @Override public CompletableFuture<Boolean> executeRtp(Player player) { return start(player, player.getWorld(), false); }
    @Override public CompletableFuture<Boolean> executeRtp(Player player, World world) { return start(player, world, true); }

    private CompletableFuture<Boolean> start(Player player, World world, boolean otherWorld) {
        var config = configSupplier.get();
        if (closed || !config.enabled()) return reject(player, config.messages().disabled());
        if (!player.hasPermission(Permissions.RTP_USE) || (otherWorld && !player.hasPermission(Permissions.RTP_WORLD)))
            return reject(player, config.messages().noPermission());
        var policy = config.worldConfigs().get(world.getName().toLowerCase(Locale.ROOT));
        if (policy == null || !policy.enabled()) return reject(player, config.messages().worldDisabled(), Placeholder.unparsed("world", world.getName()));
        if (player.isInsideVehicle()) {
            if (!policy.allowMounted()) return reject(player, config.messages().cannotUseMounted());
            player.leaveVehicle();
        }
        long remaining = getRemainingCooldownSeconds(player.getUniqueId());
        if (!player.hasPermission(Permissions.RTP_BYPASS_COOLDOWN) && remaining > 0)
            return reject(player, config.messages().cooldownActive(), Placeholder.unparsed("seconds", Long.toString(remaining)));
        var request = new Request(player, world, policy, otherWorld);
        synchronized (requests) {
            cooldownMap.entrySet().removeIf(entry -> entry.getValue() - System.nanoTime() <= 0);
            if (closed || requests.size() >= config.runtime().maxRequests() || requests.containsKey(request.player)
                || safetyInspector.isDispatched(request.player) || cooldownMap.size() >= config.runtime().maxCooldowns())
                return reject(player, config.messages().busy());
            requests.put(request.player, request);
        }
        request.result.whenComplete((value, error) -> {
            request.deadline.complete(null);
            if (request.warmup != null) request.warmup.cancel();
            requests.remove(request.player, request);
        });
        int warmup = player.hasPermission(Permissions.RTP_BYPASS_WARMUP) ? 0 : policy.warmupSeconds();
        request.deadline.orTimeout(config.runtime().timeoutSeconds() + warmup * 4L, TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null) {
                synchronized (request) {
                    request.cancelled = true;
                    if (request.dispatched) plugin.getSLF4JLogger().warn("RTP arrival unconfirmed: player={}, world={}", request.player, request.world);
                    owner(request, () -> send(request, request.dispatched ? configSupplier.get().messages().outcomeUnknown()
                        : configSupplier.get().messages().timedOut()));
                    request.result.complete(false);
                }
            }
        });
        if (warmup > 0) {
            send(request, config.messages().warmupStarted(), Placeholder.unparsed("seconds", Integer.toString(warmup)));
            try {
                request.warmup = player.getScheduler().runDelayed(plugin, task -> search(request, world, 0),
                    () -> request.result.complete(false), warmup * 20L);
                if (request.warmup == null) request.result.complete(false);
            } catch (RuntimeException rejected) { request.result.complete(false); }
        } else search(request, world, 0);
        return request.result;
    }

    private void search(Request request, World world, int attempt) {
        if (closed || request.result.isDone()) return;
        var config = configSupplier.get();
        if (attempt >= request.policy.maxAttempts()) {
            owner(request, () -> { send(request, config.messages().searchFailed()); request.result.complete(false); });
            return;
        }
        var candidate = replenisher.popCandidate(world);
        int x, z;
        if (candidate != null) { x = (int) Math.floor(candidate.x()); z = (int) Math.floor(candidate.z()); }
        else {
            if (replenisher.sampleHealth() == AdaptiveRtpReplenisher.HealthState.STRESSED) {
                owner(request, () -> { send(request, config.messages().busy()); request.result.complete(false); });
                return;
            }
            replenisher.recordDemand(world);
            double[] point = randomPoint(request.policy);
            x = (int) Math.floor(point[0]); z = (int) Math.floor(point[1]);
        }
        try {
            safetyInspector.inspectCandidate(world, request.policy, x, z, config.runtime().generateChunks())
                .whenComplete((safe, error) -> {
                    if (closed || request.result.isDone()) return;
                    if (error != null || safe == null) search(request, world, attempt + 1);
                    else owner(request, () -> depart(request, world, safe));
                });
        } catch (RuntimeException rejected) { request.result.complete(false); }
    }

    public static double[] randomPoint(RtpWorldConfig policy) {
        var rng = ThreadLocalRandom.current();
        double x, z;
        if (policy.shapeEnum() == RtpWorldConfig.Shape.SQUARE) {
            // Partition the square annulus into disjoint vertical and horizontal strips.
            double outer = policy.maxRadius(), inner = policy.minRadius();
            double verticalArea = 4 * outer * (outer - inner);
            double horizontalArea = 4 * inner * (outer - inner);
            if (rng.nextDouble(verticalArea + horizontalArea) < verticalArea) {
                x = rng.nextDouble(inner, outer) * (rng.nextBoolean() ? 1 : -1);
                z = rng.nextDouble(-outer, outer);
            } else {
                x = rng.nextDouble(-inner, inner);
                z = rng.nextDouble(inner, outer) * (rng.nextBoolean() ? 1 : -1);
            }
        } else {
            double angle = rng.nextDouble() * Math.PI * 2;
            double radius = Math.sqrt(rng.nextDouble() * ((double) policy.maxRadius() * policy.maxRadius()
                - (double) policy.minRadius() * policy.minRadius()) + (double) policy.minRadius() * policy.minRadius());
            x = radius * Math.cos(angle); z = radius * Math.sin(angle);
        }
        return new double[]{policy.centerX() + x, policy.centerZ() + z};
    }

    private void depart(Request request, World world, RtpCandidate candidate) {
        var player = request.handle.get();
        var config = configSupplier.get();
        synchronized (request) {
            if (closed || request.cancelled || request.result.isDone()) return;
            var current = config.worldConfigs().get(request.worldName.toLowerCase(Locale.ROOT));
            if (player == null || !player.isOnline() || !config.enabled() || !request.policy.equals(current)
                || !request.source.equals(player.getWorld().getUID()) || !player.hasPermission(Permissions.RTP_USE)
                || (request.otherWorld && !player.hasPermission(Permissions.RTP_WORLD)) || !candidate.worldUuid().equals(request.world)) {
                request.result.complete(false); return;
            }
            if (!safetyInspector.reserveDispatch(request.player)) {
                send(request, config.messages().busy()); request.result.complete(false); return;
            }
            request.dispatched = true;
            try {
                if (current.useChunkTickets()) ticketManager.addCandidateTickets(world, PackedLocation.fromCandidate(candidate));
                var destination = new Location(world, candidate.x(), candidate.y(), candidate.z(), candidate.yaw(), candidate.pitch());
                player.teleportAsync(destination, TeleportCause.PLUGIN).whenComplete((arrived, error) -> {
                    if (request.result.isDone()) plugin.getSLF4JLogger().info("Late RTP outcome: player={}, world={}, arrived={}, error={}",
                        request.player, request.world, arrived, error == null ? "none" : error.toString());
                    owner(request, () -> {
                        var online = request.handle.get();
                        if (Boolean.TRUE.equals(arrived) && online != null && online.isOnline() && current.cooldownSeconds() > 0) {
                            synchronized (requests) {
                                if (cooldownMap.size() < config.runtime().maxCooldowns())
                                    cooldownMap.put(request.player, System.nanoTime() + TimeUnit.SECONDS.toNanos(current.cooldownSeconds()));
                            }
                        }
                        if (!request.result.isDone() && !closed) send(request, Boolean.TRUE.equals(arrived)
                            ? config.messages().teleportSuccess() : config.messages().teleportFailed(),
                            Placeholder.unparsed("x", Integer.toString((int) Math.floor(candidate.x()))),
                            Placeholder.unparsed("y", Integer.toString((int) candidate.y())),
                            Placeholder.unparsed("z", Integer.toString((int) Math.floor(candidate.z()))),
                            Placeholder.unparsed("world", request.worldName));
                        request.result.complete(Boolean.TRUE.equals(arrived));
                    }, () -> safetyInspector.releaseDispatch(request.player));
                });
            } catch (RuntimeException rejected) {
                safetyInspector.releaseDispatch(request.player); request.result.complete(false);
            }
        }
    }

    private void owner(Request request, Runnable action) { owner(request, action, () -> {}); }
    private void owner(Request request, Runnable action, Runnable cleanup) {
        var player = request.handle.get();
        Runnable retired = () -> { cleanup.run(); request.result.complete(false); };
        if (player == null) { retired.run(); return; }
        try {
            var task = player.getScheduler().run(plugin, ignored -> {
                try { action.run(); }
                catch (RuntimeException failure) { request.result.complete(false); }
                finally { cleanup.run(); }
            }, retired);
            if (task == null) retired.run();
        } catch (RuntimeException rejected) { retired.run(); }
    }
    private CompletableFuture<Boolean> reject(Player player, String message, TagResolver... tags) {
        player.sendMessage(miniMessage.deserialize(configSupplier.get().messages().prefix() + message, tags));
        return CompletableFuture.completedFuture(false);
    }
    private void send(Request request, String message, TagResolver... tags) {
        var player = request.handle.get();
        if (player != null && player.isOnline() && message != null)
            player.sendMessage(miniMessage.deserialize(configSupplier.get().messages().prefix() + message, tags));
    }
    @Override public long getRemainingCooldownSeconds(UUID player) {
        Long until = cooldownMap.get(player);
        if (until == null) return 0;
        long nanos = until - System.nanoTime();
        if (nanos <= 0) { cooldownMap.remove(player, until); return 0; }
        return (nanos + 999_999_999L) / 1_000_000_000L;
    }
    @Override public boolean isWarmingUp(UUID player) {
        var request = requests.get(player);
        return request != null && !request.dispatched && !request.result.isDone();
    }
    @Override public boolean hasActiveWarmups() { return !requests.isEmpty(); }
    @Override public void cancelWarmup(UUID player) {
        var request = requests.get(player);
        if (request == null) return;
        synchronized (request) {
            if (request.dispatched) return;
            request.cancelled = true;
            send(request, configSupplier.get().messages().warmupCancelled());
            request.result.complete(false);
        }
    }
    @Override public void playerQuit(UUID player) {
        cancelWarmup(player); cooldownMap.remove(player);
    }
    @Override public void triggerReplenishment() { replenisher.requestAll(); }
    @Override public void shutdown() {
        closed = true;
        requests.values().forEach(request -> { synchronized (request) { request.cancelled = true; request.result.complete(false); } });
    }
}
