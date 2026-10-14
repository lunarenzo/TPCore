package com.lunatech.tpcore.module.warp.service.impl;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.cache.WarpCache;
import com.lunatech.tpcore.module.warp.cache.impl.BoundedExpiryCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService.Payment;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.economy.impl.NoOpWarpEconomyService;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.service.WarpResultStatus;
import com.lunatech.tpcore.module.warp.service.WarpService;
import com.lunatech.tpcore.util.MessageFormatter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

public final class DefaultWarpService implements WarpService {

    private final Plugin plugin;
    private final WarpRepository repository;
    private final WarpCache cache;
    private final WarpEconomyService economyService;
    private final Logger logger;
    private volatile WarpConfig config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final BoundedExpiryCache cooldowns;
    private final BoundedExpiryCache failedPasswordAttempts;
    private final Map<UUID, WarmupSession> activeWarmups = new ConcurrentHashMap<>();
    private final Set<String> inFlightMutations = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> acceptedWork = ConcurrentHashMap.newKeySet();
    private final AtomicReference<CompletableFuture<?>> activeMigration = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> initialization = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> closing = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean ready;
    private final Semaphore admission;
    private final AtomicInteger reservedCreates = new AtomicInteger();
    private final StampedLock storageAccess = new StampedLock();
    private final String storageType;

    private record Destination(UUID worldId, double x, double y, double z, float yaw, float pitch) {
        static Destination snapshot(Location location) {
            return new Destination(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }
    }
    private enum Phase { RESERVED, WARMUP, PREPARING, TELEPORTING, FINISHED }
    private static final class WarmupSession {
        final UUID id;
        final Warp warp;
        final WarpConfig config;
        final boolean chargeOnSuccess;
        final double quotedCost;
        final boolean applyCooldown;
        final CompletableFuture<WarpResultStatus> result = new CompletableFuture<>();
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RESERVED);
        final AtomicReference<CompletableFuture<Boolean>> settlement = new AtomicReference<>();
        final AtomicInteger workCount = new AtomicInteger(2);
        volatile ScheduledTask task;
        volatile Payment payment;
        WarmupSession(UUID id, Warp warp, WarpConfig config, boolean chargeOnSuccess, double quotedCost) {
            this(id, warp, config, chargeOnSuccess, quotedCost, true);
        }
        WarmupSession(UUID id, Warp warp, WarpConfig config, boolean chargeOnSuccess, double quotedCost, boolean applyCooldown) {
            this.id = id; this.warp = warp; this.config = config;
            this.chargeOnSuccess = chargeOnSuccess; this.quotedCost = quotedCost;
            this.applyCooldown = applyCooldown;
        }
    }

    private void recordCooldown(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis() + config.cooldownSeconds() * 1000L);
    }

    public DefaultWarpService(Plugin plugin, WarpRepository repository, WarpCache cache, WarpEconomyService economyService, WarpConfig config, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.economyService = Objects.requireNonNull(economyService, "economyService cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.admission = new Semaphore(config.maxConcurrentOperations());
        this.cooldowns = new BoundedExpiryCache(config.maxPlayerStateEntries());
        this.failedPasswordAttempts = new BoundedExpiryCache(config.maxPlayerStateEntries());
        this.storageType = config.storage() != null && "YAML".equalsIgnoreCase(config.storage().type()) ? "YAML" : "SQLITE";
        cache.setCapacity(config.maxCachedWarps());
    }

    public DefaultWarpService(Plugin plugin, WarpRepository repository, WarpCache cache, WarpConfig config, Logger logger) {
        this(plugin, repository, cache, new NoOpWarpEconomyService(), config, logger);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        CompletableFuture<Void> existing = initialization.get();
        if (existing != null) return existing;
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!initialization.compareAndSet(null, result)) return initialization.get();
        repository.initialize().thenCompose(ignored -> repository.loadAll()).whenComplete((warps, failure) -> {
            if (failure != null) result.completeExceptionally(failure);
            else if (closed.get()) result.completeExceptionally(new IllegalStateException("Warp service closed during initialization"));
            else {
                try { cache.populate(warps); ready = !closed.get(); if (!ready) cache.clear(); result.complete(null); }
                catch (RuntimeException invalid) { result.completeExceptionally(invalid); }
            }
        });
        return result;
    }

    @Override public boolean isReady() { return ready && !closed.get(); }

    @Override
    public CompletableFuture<WarpResultStatus> teleportToWarp(Player player, String warpName, String rawPassword) {
        if (!ready || closed.get()) return completed(WarpResultStatus.NOT_READY);
        WarpConfig settings = config;
        if (!settings.enabled()) return completed(WarpResultStatus.NOT_READY);
        Optional<Warp> found = getWarp(warpName);
        if (found.isEmpty()) return completed(WarpResultStatus.WARP_NOT_FOUND);
        Warp warp = found.get();
        UUID id = player.getUniqueId();
        if (activeWarmups.containsKey(id)) return completed(WarpResultStatus.WARMUP_ALREADY_ACTIVE);
        if (warp.permissionGated() && !player.hasPermission("tpcore.warp." + warp.name().toLowerCase(Locale.ROOT))
            && !player.hasPermission("tpcore.warp.admin")) return completed(WarpResultStatus.NO_PERMISSION);
        if (warp.hasPassword() && !player.hasPermission("tpcore.warp.admin") && !player.hasPermission("tpcore.warp.bypass.password")) {
            if (failedPasswordAttempts.get(id) != null) return completed(WarpResultStatus.INVALID_PASSWORD);
            if (rawPassword == null || rawPassword.isBlank()) return completed(WarpResultStatus.PASSWORD_REQUIRED);
            if (!warp.passwordHash().equals(hashPassword(warp.name(), rawPassword)) && !warp.passwordHash().equals(hashLegacyPassword(rawPassword))) {
                failedPasswordAttempts.put(id, System.currentTimeMillis() + 2000L);
                return completed(WarpResultStatus.INVALID_PASSWORD);
            }
        }
        if (cooldowns.get(id) != null && !player.hasPermission("tpcore.warp.bypass.cooldown")) return completed(WarpResultStatus.COOLDOWN_ACTIVE);
        if (resolveWorld(warp) == null) return completed(WarpResultStatus.WORLD_NOT_LOADED);
        double cost = economyService.getWarpCost(player);
        if (cost > 0 && !economyService.isAvailable()) return completed(WarpResultStatus.ECONOMY_UNAVAILABLE);
        if (!admission.tryAcquire()) return completed(WarpResultStatus.BUSY);
        WarmupSession session = new WarmupSession(id, warp, settings, "CHARGE_ON_SUCCESS".equals(settings.getNormalizedChargeTiming()), cost);
        if (activeWarmups.putIfAbsent(id, session) != null) { admission.release(); return completed(WarpResultStatus.WARMUP_ALREADY_ACTIVE); }
        if (!cooldowns.put(id, Long.MAX_VALUE)) { activeWarmups.remove(id, session); admission.release(); return completed(WarpResultStatus.BUSY); }
        boolean bypass = player.hasPermission("tpcore.warp.bypass.warmup");
        session.result.whenComplete((status, failure) -> {
            if (status != WarpResultStatus.SUCCESS) cooldowns.remove(id);
            releaseSessionWork(session);
        });
        CompletableFuture<?> funding = session.chargeOnSuccess
            ? economyService.validateExact(id, cost).thenAccept(valid -> {
                if (!valid) finish(session, WarpResultStatus.INSUFFICIENT_FUNDS);
                else schedulePlayer(session, current -> beginWarmup(session, current, bypass));
            })
            : economyService.chargeExact(id, cost).thenCompose(payment -> {
                session.payment = payment;
                if (payment.successful()) notifyPayment(session.id, payment.amount(), false, session.config);
                if (!payment.successful()) { finish(session, WarpResultStatus.INSUFFICIENT_FUNDS); return CompletableFuture.completedFuture(false); }
                if (session.result.isDone() || closed.get()) return settle(session, true);
                schedulePlayer(session, current -> beginWarmup(session, current, bypass));
                return CompletableFuture.completedFuture(true);
            });
        track(session, funding).exceptionally(failure -> { logger.error("Warp payment failed for {}", id, failure); finish(session, WarpResultStatus.ERROR); return null; });
        releaseSessionWork(session);
        armTimeout(session, Math.max(0L, settings.warmupSeconds()) + settings.operationTimeoutSeconds());
        return session.result;
    }

    private void beginWarmup(WarmupSession session, Player player, boolean bypass) {
        if (session.result.isDone() || closed.get()) { cancel(session, WarpResultStatus.ERROR); return; }
        if (bypass || session.config.warmupSeconds() <= 0) { prepare(session); return; }
        session.phase.set(Phase.WARMUP);
        sendPlayerMessage(player, session.config.messages().warmupStart(),
            Placeholder.unparsed("warp", session.warp.name()), Placeholder.unparsed("seconds", String.valueOf(session.config.warmupSeconds())));
        try {
            session.task = player.getScheduler().runDelayed(plugin, task -> prepare(session),
                () -> cancel(session, WarpResultStatus.ERROR), session.config.warmupSeconds() * 20L);
            if (session.task == null) cancel(session, WarpResultStatus.ERROR);
            else if (session.result.isDone()) session.task.cancel();
        } catch (RuntimeException failure) { cancel(session, WarpResultStatus.ERROR); }
    }

    private void prepare(WarmupSession session) {
        if (session.result.isDone() || closed.get()) { cancel(session, WarpResultStatus.ERROR); return; }
        session.phase.set(Phase.PREPARING);
        World world = resolveWorld(session.warp);
        if (world == null) { finish(session, WarpResultStatus.WORLD_NOT_LOADED); return; }
        Location target = new Location(world, session.warp.x(), session.warp.y(), session.warp.z(), session.warp.yaw(), session.warp.pitch());
        CompletableFuture<Location> safe = session.config.safetyChecks().preventUnsafeTeleport()
            ? WarpSafetyInspector.findSafeLocationAsync(plugin, target, true, 3,
                session.config.safetyChecks().preventNetherRoof(), session.config.safetyChecks().maxNetherHeight())
            : CompletableFuture.completedFuture(target);
        track(session, safe.thenCompose(location -> {
            if (location == null) { finish(session, WarpResultStatus.UNSAFE_LOCATION); return CompletableFuture.completedFuture(false); }
            if (session.result.isDone() || closed.get()) return settle(session, true);
            Destination destination = Destination.snapshot(location);
            if (session.chargeOnSuccess && session.payment == null) {
                return economyService.chargeExact(session.id, session.quotedCost).thenCompose(payment -> {
                    session.payment = payment;
                    if (!payment.successful()) { finish(session, WarpResultStatus.INSUFFICIENT_FUNDS); return CompletableFuture.completedFuture(false); }
                    if (session.result.isDone() || closed.get()) return settle(session, true);
                    dispatchTeleport(session, destination);
                    return CompletableFuture.completedFuture(true);
                });
            }
            dispatchTeleport(session, destination);
            return CompletableFuture.completedFuture(true);
        })).exceptionally(failure -> { logger.error("Warp preparation failed for {}", session.id, failure); finish(session, WarpResultStatus.ERROR); return null; });
    }

    private void dispatchTeleport(WarmupSession session, Destination target) {
        schedulePlayer(session, player -> {
            if (!session.phase.compareAndSet(Phase.PREPARING, Phase.TELEPORTING)) return;
            World world = Bukkit.getWorld(target.worldId());
            if (world == null) { finish(session, WarpResultStatus.WORLD_NOT_LOADED); return; }
            if (player.isInsideVehicle()) player.leaveVehicle();
            if (player.isSleeping()) player.wakeup(false);
            if (player.isGliding()) player.setGliding(false);
            player.setFallDistance(0.0f);
            track(session, player.teleportAsync(new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch())).thenCompose(success -> {
                WarpResultStatus status = Boolean.TRUE.equals(success) ? WarpResultStatus.SUCCESS : WarpResultStatus.ERROR;
                if (status == WarpResultStatus.SUCCESS && !closed.get()) {
                    if (session.applyCooldown) recordCooldown(session.id);
                    if (session.chargeOnSuccess && session.payment != null) notifyPayment(session.id, session.payment.amount(), false, session.config);
                }
                return settle(session, status != WarpResultStatus.SUCCESS && (session.chargeOnSuccess || session.config.refundOnCancel() || closed.get()))
                    .thenAccept(settled -> finishWithoutSettlement(session, settled ? status : WarpResultStatus.ERROR));
            })).exceptionally(failure -> { logger.error("Warp teleport failed for {}", session.id, failure); finish(session, WarpResultStatus.ERROR); return null; });
        });
    }

    private void schedulePlayer(WarmupSession session, Consumer<Player> action) {
        if (session.result.isDone() || closed.get()) { cancel(session, WarpResultStatus.ERROR); return; }
        Player handle = Bukkit.getPlayer(session.id);
        if (handle == null) { cancel(session, WarpResultStatus.ERROR); return; }
        try {
            ScheduledTask task = handle.getScheduler().run(plugin, ignored -> {
                Player current = Bukkit.getPlayer(session.id);
                if (current == null || closed.get() || session.result.isDone()) cancel(session, WarpResultStatus.ERROR);
                else { try { action.accept(current); } catch (RuntimeException failure) { finish(session, WarpResultStatus.ERROR); } }
            }, () -> cancel(session, WarpResultStatus.ERROR));
            if (task == null) cancel(session, WarpResultStatus.ERROR);
        } catch (RuntimeException failure) { cancel(session, WarpResultStatus.ERROR); }
    }

    private CompletableFuture<Boolean> settle(WarmupSession session, boolean refund) {
        Payment payment = session.payment;
        if (payment == null) return CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> existing = session.settlement.get();
        if (existing != null) return existing;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!session.settlement.compareAndSet(null, result)) return session.settlement.get();
        track(session, result);
        try { economyService.settle(payment, refund).whenComplete((settled, failure) -> {
            if (failure == null) {
                if (refund && Boolean.TRUE.equals(settled)) notifyPayment(session.id, payment.amount(), true, session.config);
                result.complete(settled);
            } else result.completeExceptionally(failure);
        }); } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    private void finish(WarmupSession session, WarpResultStatus status) {
        session.phase.set(Phase.FINISHED);
        settle(session, session.chargeOnSuccess || session.config.refundOnCancel() || closed.get()).whenComplete((settled, failure) ->
            finishWithoutSettlement(session, failure == null ? status : WarpResultStatus.ERROR));
    }
    private void finishWithoutSettlement(WarmupSession session, WarpResultStatus status) {
        session.phase.set(Phase.FINISHED);
        session.result.complete(status);
    }
    private void cancel(WarmupSession session, WarpResultStatus status) {
        Phase phase;
        do { phase = session.phase.get(); if (phase == Phase.TELEPORTING || phase == Phase.FINISHED) return; }
        while (!session.phase.compareAndSet(phase, Phase.FINISHED));
        if (session.task != null) session.task.cancel();
        finish(session, status);
    }
    private void releaseSessionWork(WarmupSession session) {
        if (session.workCount.decrementAndGet() == 0) {
            activeWarmups.remove(session.id, session); admission.release();
        }
    }
    private <T> CompletableFuture<T> track(WarmupSession session, CompletableFuture<T> future) {
        session.workCount.incrementAndGet();
        future.whenComplete((value, failure) -> releaseSessionWork(session));
        return track(future);
    }
    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        acceptedWork.add(barrier);
        future.whenComplete((value, failure) -> { barrier.complete(null); acceptedWork.remove(barrier); });
        return future;
    }
    private CompletableFuture<WarpResultStatus> completed(WarpResultStatus status) { return CompletableFuture.completedFuture(status); }

    private void armTimeout(WarmupSession session, long seconds) {
        CompletableFuture<Void> deadline = new CompletableFuture<>();
        deadline.orTimeout(seconds, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
            if (failure != null) {
                if (session.phase.get() == Phase.TELEPORTING) session.result.complete(WarpResultStatus.ERROR);
                else cancel(session, WarpResultStatus.ERROR);
            }
        });
        session.result.whenComplete((status, failure) -> deadline.complete(null));
    }

    @Override
    public CompletableFuture<WarpResultStatus> teleportOtherToWarp(Player sender, Player target, String warpName) {
        if (!ready || closed.get()) return completed(WarpResultStatus.NOT_READY);
        Optional<Warp> warp = getWarp(warpName);
        if (warp.isEmpty()) return completed(WarpResultStatus.WARP_NOT_FOUND);
        UUID id = target.getUniqueId();
        if (!admission.tryAcquire()) return completed(WarpResultStatus.BUSY);
        WarmupSession session = new WarmupSession(id, warp.get(), config, false, 0.0, false);
        if (activeWarmups.putIfAbsent(id, session) != null) { admission.release(); return completed(WarpResultStatus.WARMUP_ALREADY_ACTIVE); }
        session.result.whenComplete((value, failure) -> releaseSessionWork(session));
        schedulePlayer(session, ignored -> prepare(session));
        releaseSessionWork(session);
        armTimeout(session, config.operationTimeoutSeconds());
        return session.result;
    }

    @Override
    public CompletableFuture<WarpResultStatus> setWarp(Player creator, String warpName, boolean overwrite, String rawPassword, String category) {
        if (!ready || closed.get()) return completed(WarpResultStatus.NOT_READY);
        if (!isValidWarpName(warpName)) return completed(WarpResultStatus.INVALID_NAME);
        long stamp = storageAccess.tryReadLock();
        if (stamp == 0) return completed(WarpResultStatus.BUSY);
        if (!admission.tryAcquire()) { storageAccess.unlockRead(stamp); return completed(WarpResultStatus.BUSY); }
        String name = warpName.toLowerCase(Locale.ROOT);
        if (!inFlightMutations.add(name)) { admission.release(); storageAccess.unlockRead(stamp); return completed(WarpResultStatus.BUSY); }
        Optional<Warp> existing = cache.getWarp(name);
        if (existing.isPresent() && !overwrite) return releaseMutation(name, stamp, WarpResultStatus.ALREADY_EXISTS);
        boolean creating = existing.isEmpty();
        int reserved = creating ? reservedCreates.incrementAndGet() : reservedCreates.get();
        WarpConfig settings = config;
        if (creating && (cache.getWarpCount() + reserved > settings.maxCachedWarps()
            || settings.maxWarps() > 0 && cache.getWarpCount() + reserved > settings.maxWarps()
                && !creator.hasPermission("tpcore.warp.bypass.limit") && !creator.hasPermission("tpcore.warp.admin"))) {
            reservedCreates.decrementAndGet(); return releaseMutation(name, stamp, WarpResultStatus.LIMIT_REACHED);
        }
        Warp warp;
        double cost;
        try {
            Location loc = creator.getLocation();
            warp = new Warp(warpName, loc.getWorld().getUID(), loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(), creator.getUniqueId(), category == null || category.isBlank() ? settings.defaultCategory() : category,
                rawPassword == null || rawPassword.isBlank() ? null : hashPassword(warpName, rawPassword), settings.defaultPermissionGated(), System.currentTimeMillis());
            cost = economyService.getSetWarpCost(creator);
        } catch (RuntimeException failure) {
            if (creating) reservedCreates.decrementAndGet();
            return releaseMutation(name, stamp, WarpResultStatus.ERROR);
        }
        if (cost > 0 && !economyService.isAvailable()) {
            if (creating) reservedCreates.decrementAndGet();
            return releaseMutation(name, stamp, WarpResultStatus.ECONOMY_UNAVAILABLE);
        }
        CompletableFuture<WarpResultStatus> work = economyService.chargeExact(warp.creatorUuid(), cost).thenCompose(payment -> {
            if (!payment.successful()) return completed(WarpResultStatus.INSUFFICIENT_FUNDS);
            notifyPayment(warp.creatorUuid(), payment.amount(), false, settings);
            if (closed.get()) return economyService.settle(payment, true).thenApply(done -> WarpResultStatus.ERROR);
            return repository.save(warp).handle((ignored, failure) -> failure).thenCompose(failure -> {
                if (failure == null && !closed.get()) cache.putWarp(warp);
                return economyService.settle(payment, failure != null).thenApply(done -> {
                    if (failure != null && done) notifyPayment(warp.creatorUuid(), payment.amount(), true, settings);
                    return failure == null && done ? WarpResultStatus.SUCCESS : WarpResultStatus.ERROR;
                });
            });
        }).exceptionally(failure -> { logger.error("Failed to create warp {}", name, failure); return WarpResultStatus.ERROR; });
        work = work.whenComplete((value, failure) -> {
            if (creating) reservedCreates.decrementAndGet();
            inFlightMutations.remove(name); admission.release(); storageAccess.unlockRead(stamp);
        });
        return track(work);
    }

    private CompletableFuture<WarpResultStatus> releaseMutation(String name, long stamp, WarpResultStatus status) {
        inFlightMutations.remove(name); admission.release(); storageAccess.unlockRead(stamp); return completed(status);
    }

    @Override
    public CompletableFuture<WarpResultStatus> deleteWarp(String warpName) {
        if (!ready || closed.get()) return completed(WarpResultStatus.NOT_READY);
        if (!isValidWarpName(warpName)) return completed(WarpResultStatus.WARP_NOT_FOUND);
        long stamp = storageAccess.tryReadLock();
        if (stamp == 0) return completed(WarpResultStatus.BUSY);
        if (!admission.tryAcquire()) { storageAccess.unlockRead(stamp); return completed(WarpResultStatus.BUSY); }
        String name = warpName.toLowerCase(Locale.ROOT);
        if (!inFlightMutations.add(name)) { admission.release(); storageAccess.unlockRead(stamp); return completed(WarpResultStatus.BUSY); }
        if (cache.getWarp(name).isEmpty()) return releaseMutation(name, stamp, WarpResultStatus.WARP_NOT_FOUND);
        return track(repository.delete(name).thenApply(ignored -> {
            if (!closed.get()) cache.removeWarp(name);
            return WarpResultStatus.SUCCESS;
        }).exceptionally(failure -> { logger.error("Failed to delete warp {}", name, failure); return WarpResultStatus.ERROR; })
            .whenComplete((value, failure) -> { inFlightMutations.remove(name); admission.release(); storageAccess.unlockRead(stamp); }));
    }

    @Override public void cancelWarmupOnMove(Player player) {
        WarmupSession session = activeWarmups.get(player.getUniqueId());
        if (session != null && session.phase.get() != Phase.TELEPORTING) {
            cancel(session, WarpResultStatus.WARMUP_CANCELLED_MOVE);
            sendPlayerMessage(player, session.config.messages().warmupCancelledMove());
        }
    }
    @Override public void cancelWarmupOnDamage(Player player) {
        WarmupSession session = activeWarmups.get(player.getUniqueId());
        if (session != null && session.phase.get() != Phase.TELEPORTING) {
            cancel(session, WarpResultStatus.WARMUP_CANCELLED_DAMAGE);
            sendPlayerMessage(player, session.config.messages().warmupCancelledDamage());
        }
    }
    @Override public void cancelWarmupOnQuit(UUID id) {
        WarmupSession session = activeWarmups.get(id);
        if (session != null) cancel(session, WarpResultStatus.ERROR);
        failedPasswordAttempts.remove(id);
        cooldowns.get(id); // Prune expired identity-only cooldowns; retain live cooldowns across reconnects.
    }
    @Override public long getRemainingCooldownSeconds(UUID id) {
        Long expiry = id == null ? null : cooldowns.get(id);
        return expiry == null || expiry == Long.MAX_VALUE ? 0 : Math.max(1, (expiry - System.currentTimeMillis() + 999) / 1000);
    }

    @Override
    public Optional<Warp> getWarp(String warpName) {
        if (!isValidWarpName(warpName)) {
            return Optional.empty();
        }
        return cache.getWarp(warpName);
    }

    @Override
    public Collection<Warp> getAllWarps() {
        return cache.getAllWarps();
    }

    @Override
    public List<Warp> getWarpsByCategory(String category) {
        return cache.getWarpsByCategory(category);
    }

    @Override
    public Set<String> getCategories() {
        return cache.getCategories();
    }

    @Override
    public void updateConfig(WarpConfig newConfig) {
        if (newConfig != null) {
            cache.setCapacity(newConfig.maxCachedWarps());
            this.config = newConfig;
        }
    }

    @Override
    public CompletableFuture<Integer> migrateData(String fromStorage, String toStorage) {
        if (!ready || closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Warp storage is not ready"));
        String from = fromStorage == null ? "" : fromStorage.trim().toUpperCase(Locale.ROOT);
        String to = toStorage == null ? "" : toStorage.trim().toUpperCase(Locale.ROOT);
        if (!isValidStorageType(from) || !isValidStorageType(to)) return CompletableFuture.failedFuture(new IllegalArgumentException("INVALID_STORAGE_TYPE"));
        if (from.equals(to)) return CompletableFuture.failedFuture(new IllegalArgumentException("SAME_STORAGE_TYPE"));
        long stamp = storageAccess.tryWriteLock();
        if (stamp == 0) return CompletableFuture.failedFuture(new IllegalStateException("Warp storage is busy"));
        WarpRepository source = from.equals(storageType) ? repository : createRepoForType(from);
        WarpRepository target = to.equals(storageType) ? repository : createRepoForType(to);
        CompletableFuture<Void> sourceReady = source == repository ? CompletableFuture.completedFuture(null) : source.initialize();
        CompletableFuture<Void> targetReady = target == repository ? CompletableFuture.completedFuture(null) : target.initialize();
        CompletableFuture<Integer> migration = CompletableFuture.allOf(sourceReady, targetReady).thenCompose(ignored -> source.loadAll())
            .thenCompose(warps -> target.loadAll().thenCompose(destination -> {
                Set<String> merged = ConcurrentHashMap.newKeySet(); merged.addAll(destination.keySet()); merged.addAll(warps.keySet());
                if (merged.size() > config.maxCachedWarps()) return CompletableFuture.failedFuture(new IllegalStateException("Migration exceeds warp cache capacity"));
                return target.saveAll(warps.values()).thenCompose(done -> target.loadAll()).thenApply(saved -> {
                    if (target == repository && !closed.get()) cache.populate(saved);
                    return warps.size();
                });
            }));
        activeMigration.set(migration);
        CompletableFuture<Integer> result = migration.handle((count, failure) -> {
            CompletableFuture<Void> sourceClose = source == repository ? CompletableFuture.completedFuture(null) : source.close();
            CompletableFuture<Void> targetClose = target == repository ? CompletableFuture.completedFuture(null) : target.close();
            return CompletableFuture.allOf(sourceClose, targetClose).thenApply(ignored -> {
                if (failure != null) throw new CompletionException(failure);
                return count;
            });
        }).thenCompose(value -> value).whenComplete((value, failure) -> {
            activeMigration.set(null); storageAccess.unlockWrite(stamp);
        });
        return track(result);
    }

    private boolean isValidStorageType(String type) {
        return "SQLITE".equalsIgnoreCase(type) || "YAML".equalsIgnoreCase(type);
    }

    private WarpRepository createRepoForType(String type) {
        if ("YAML".equalsIgnoreCase(type)) {
            return new YamlWarpRepository(plugin.getDataFolder(), logger, config.storageQueueCapacity(), config.maxCachedWarps(), config.maxStorageBytes());
        } else {
            return new SqliteWarpRepository(plugin.getDataFolder(), logger, config.storageQueueCapacity(), config.maxCachedWarps());
        }
    }

    @Override
    public CompletableFuture<Void> close() {
        CompletableFuture<Void> previous = closing.get();
        if (previous != null) return previous;
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!closing.compareAndSet(null, result)) return closing.get();
        closed.set(true); ready = false;
        activeWarmups.values().forEach(session -> cancel(session, WarpResultStatus.ERROR));
        cache.clear(); cooldowns.clear(); failedPasswordAttempts.clear();
        CompletableFuture.allOf(acceptedWork.toArray(CompletableFuture[]::new))
            .orTimeout(config.operationTimeoutSeconds(), TimeUnit.SECONDS)
            .handle((ignored, failure) -> {
                if (failure != null) logger.error("Warp work did not finish cleanly during shutdown; payment journal requires reconciliation", failure);
                return null;
            }).thenCompose(ignored -> economyService.close())
            .handle((ignored, failure) -> {
                if (failure != null) logger.error("Warp economy did not close cleanly", failure);
                return null;
            }).thenCompose(ignored -> repository.close())
            .whenComplete((ignored, failure) -> {
                ready = false; cache.clear(); cooldowns.clear(); failedPasswordAttempts.clear(); activeWarmups.clear(); acceptedWork.clear();
                if (failure == null) result.complete(null); else result.completeExceptionally(failure);
            });
        return result;
    }

    private World resolveWorld(Warp warp) {
        if (Bukkit.getServer() == null) {
            return null;
        }
        if (warp.worldId() != null) {
            World w = Bukkit.getWorld(warp.worldId());
            if (w != null) return w;
        }
        return Bukkit.getWorld(warp.worldName());
    }

    private boolean isValidWarpName(String name) {
        if (name == null || name.isBlank() || name.length() > 32) {
            return false;
        }
        return name.matches("^[a-zA-Z0-9_-]+$");
    }

    private String hashPassword(String warpName, String password) {
        if (password == null || password.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String salted = (warpName != null ? warpName.toLowerCase(Locale.ROOT) : "") + ":" + password + ":tpcore_warp_salt";
            byte[] hash = digest.digest(salted.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("Failed to compute SHA-256 hash", e);
            return password;
        }
    }

    private String hashLegacyPassword(String password) {
        if (password == null || password.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("Failed to compute legacy SHA-256 hash", e);
            return password;
        }
    }

    private void notifyPayment(UUID id, double amount, boolean refund, WarpConfig settings) {
        if (amount <= 0 || closed.get() || !plugin.isEnabled()) return;
        Player handle = Bukkit.getPlayer(id);
        if (handle == null) return;
        handle.getScheduler().run(plugin, ignored -> {
            Player current = Bukkit.getPlayer(id);
            if (current != null) sendPlayerMessage(current, refund ? settings.messages().costRefunded() : settings.messages().costDeducted(),
                Placeholder.unparsed("cost", economyService.format(amount)));
        }, null);
    }

    private void sendPlayerMessage(Player player, String template, TagResolver... customResolvers) {
        if (player == null || !player.isOnline() || template == null || template.isBlank()) {
            return;
        }
        TagResolver prefixResolver = Placeholder.parsed("prefix", MessageFormatter.toMiniMessage(config.messages().prefix()));
        TagResolver combined;
        if (customResolvers == null || customResolvers.length == 0) {
            combined = prefixResolver;
        } else {
            TagResolver[] all = new TagResolver[customResolvers.length + 1];
            all[0] = prefixResolver;
            System.arraycopy(customResolvers, 0, all, 1, customResolvers.length);
            combined = TagResolver.resolver(all);
        }
        player.sendMessage(this.miniMessage.deserialize(MessageFormatter.toMiniMessage(template), combined));
    }
}
