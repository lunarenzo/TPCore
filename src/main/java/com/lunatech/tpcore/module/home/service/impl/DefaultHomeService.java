package com.lunatech.tpcore.module.home.service.impl;

import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.home.engine.ConcurrentTeleportPipelineEngine;
import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.model.HomePage;
import com.lunatech.tpcore.module.home.repository.HomeRepository;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import com.lunatech.tpcore.module.home.service.HomeService;
import com.lunatech.tpcore.util.MessageFormatter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class DefaultHomeService implements HomeService {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.+-]+");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Plugin plugin;
    private final HomeRepository repository;
    private final Supplier<HomeConfig> settings;
    private final ConcurrentTeleportPipelineEngine engine;
    private final CompletableFuture<Void> initialized;
    private final Object admission = new Object();
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private volatile boolean closed;

    private enum Phase { PREPARING, DISPATCHED, DONE }
    private static final class Request {
        final UUID actor, owner;
        final String name, permission;
        final HomeConfig config;
        final boolean trackCooldown;
        final CompletableFuture<HomeResultStatus> result = new CompletableFuture<>();
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PREPARING);
        volatile CompletableFuture<?> observation;
        volatile ScheduledTask warmup;
        Request(UUID actor, UUID owner, String name, String permission, HomeConfig config, boolean trackCooldown) {
            this.actor = actor; this.owner = owner; this.name = name; this.permission = permission; this.config = config;
            this.trackCooldown = trackCooldown;
        }
    }

    public DefaultHomeService(Plugin plugin, HomeRepository repository, Supplier<HomeConfig> settings, ConcurrentTeleportPipelineEngine engine) {
        this.plugin = plugin; this.repository = repository; this.settings = settings; this.engine = engine;
        initialized = repository.initialize();
        initialized.whenComplete((ignored, error) -> {
            if (error != null) plugin.getSLF4JLogger().error("Home storage initialization failed", error);
        });
    }

    public boolean ready() { return !closed && initialized.isDone() && !initialized.isCompletedExceptionally(); }
    private HomeResultStatus availability() {
        if (closed || !settings.get().enabled()) return HomeResultStatus.DISABLED;
        if (!initialized.isDone()) return HomeResultStatus.NOT_READY;
        return initialized.isCompletedExceptionally() ? HomeResultStatus.STORAGE_ERROR : HomeResultStatus.SUCCESS;
    }

    @Override public CompletableFuture<HomeResultStatus> setHome(Player player, String name, boolean force) {
        return saveHome(player, name, force || !settings.get().requireOverwriteConfirmation());
    }

    public CompletableFuture<HomeResultStatus> createHome(Player player, String name) {
        if (!player.hasPermission(Permissions.HOME_SET)) return done(HomeResultStatus.NO_PERMISSION);
        return saveHome(player, name, false);
    }

    private CompletableFuture<HomeResultStatus> saveHome(Player player, String name, boolean overwrite) {
        if (!valid(name) || name.length() > 32) return done(HomeResultStatus.INVALID_NAME);
        HomeConfig config = settings.get();
        var location = player.getLocation();
        var world = location.getWorld();
        if (restricted(world.getName(), config)) return done(HomeResultStatus.WORLD_RESTRICTED);
        UUID owner = player.getUniqueId();
        Home home = new Home(owner, name, world.getName(), world.getUID(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch(), System.currentTimeMillis());
        int limit = homeLimit(player, config);
        return mutate(owner, name, () -> repository.save(home, overwrite, limit));
    }

    @Override public CompletableFuture<HomeResultStatus> deleteHome(Player player, String name) {
        if (!valid(name)) return done(HomeResultStatus.INVALID_NAME);
        UUID owner = player.getUniqueId();
        var runtime = settings.get().runtime();
        return mutate(owner, name, () -> repository.archive(owner, name, runtime.recoveryCapacity(), TimeUnit.DAYS.toMillis(runtime.recoveryDays())));
    }

    public CompletableFuture<HomeResultStatus> deleteHome(Player player, HomePage.Entry expected) {
        Home home = expected.home();
        UUID owner = player.getUniqueId();
        if (!owner.equals(home.ownerUuid()) || !player.hasPermission(Permissions.HOME_DEL)) return done(HomeResultStatus.NO_PERMISSION);
        var runtime = settings.get().runtime();
        return mutate(owner, home.name(), () -> repository.archiveMatching(expected, runtime.recoveryCapacity(),
            TimeUnit.DAYS.toMillis(runtime.recoveryDays())));
    }

    @Override public CompletableFuture<HomeResultStatus> restoreHome(Player player, String name) {
        if (!valid(name)) return done(HomeResultStatus.INVALID_NAME);
        UUID owner = player.getUniqueId();
        HomeConfig config = settings.get();
        int limit = homeLimit(player, config);
        return mutate(owner, name, () -> repository.restore(owner, name, limit, TimeUnit.DAYS.toMillis(config.runtime().recoveryDays())));
    }

    private CompletableFuture<HomeResultStatus> mutate(UUID owner, String name, Supplier<CompletableFuture<HomeResultStatus>> write) {
        List<Request> invalidated = new ArrayList<>();
        CompletableFuture<HomeResultStatus> result;
        synchronized (admission) {
            HomeResultStatus available = availability();
            if (available != HomeResultStatus.SUCCESS) return done(available);
            result = write.get();
            if (!result.isCompletedExceptionally()) {
                for (Request request : requests.values()) {
                    if (owner.equals(request.owner) && name.equalsIgnoreCase(request.name)
                        && request.phase.compareAndSet(Phase.PREPARING, Phase.DONE)) invalidated.add(request);
                }
            }
        }
        invalidated.forEach(request -> finish(request, HomeResultStatus.CANCELLED));
        return result.exceptionally(error -> storageFailure("write", error));
    }

    @Override public CompletableFuture<List<Home>> list(UUID owner, int limit, long offset) {
        synchronized (admission) {
            HomeResultStatus available = availability();
            if (available != HomeResultStatus.SUCCESS) return CompletableFuture.failedFuture(new HomeUnavailable(available));
            return repository.list(owner, limit, offset);
        }
    }

    public CompletableFuture<HomePage> page(UUID owner, long page) {
        synchronized (admission) {
            HomeResultStatus available = availability();
            if (available != HomeResultStatus.SUCCESS) return CompletableFuture.failedFuture(new HomeUnavailable(available));
            return repository.page(owner, page);
        }
    }

    public CompletableFuture<HomeResultStatus> teleport(Player player, HomePage.Entry expected) {
        UUID owner = player.getUniqueId();
        if (!owner.equals(expected.home().ownerUuid())) return done(HomeResultStatus.NO_PERMISSION);
        return teleport(player, owner, expected.home().name(), Permissions.HOME_USE, false, expected);
    }

    @Override public CompletableFuture<HomeResultStatus> teleport(Player player, UUID owner, String name, String permission, boolean respawn) {
        return teleport(player, owner, name, permission, respawn, null);
    }

    private CompletableFuture<HomeResultStatus> teleport(Player player, UUID owner, String name, String permission, boolean respawn,
        HomePage.Entry expected) {
        if (!valid(name)) return done(HomeResultStatus.INVALID_NAME);
        HomeConfig config = settings.get();
        UUID actor = player.getUniqueId();
        if (permission != null && !player.hasPermission(permission)) return done(HomeResultStatus.NO_PERMISSION);
        boolean bypassCooldown = respawn || player.hasPermission(Permissions.HOME_BYPASS_COOLDOWN);
        int warmup = respawn || player.hasPermission(Permissions.HOME_BYPASS_WARMUP) ? 0 : Math.clamp(config.warmupSeconds(), 0, 300);
        Request request;
        CompletableFuture<Optional<Home>> lookup;
        synchronized (admission) {
            HomeResultStatus available = availability();
            if (available != HomeResultStatus.SUCCESS) return done(available);
            if (engine.isDispatched(actor)) return done(HomeResultStatus.OUTCOME_UNKNOWN);
            if (!bypassCooldown && remainingCooldown(actor) > 0) return done(HomeResultStatus.COOLDOWN_ACTIVE);
            if (requests.containsKey(actor) || requests.size() >= config.runtime().maxRequests()) return done(HomeResultStatus.BUSY);
            if (cooldowns.size() + requests.size() >= config.runtime().maxCooldowns()) {
                long now = System.nanoTime();
                cooldowns.values().removeIf(expiry -> expiry - now <= 0);
            }
            if (!bypassCooldown && !cooldowns.containsKey(actor)
                && cooldowns.size() + requests.size() >= config.runtime().maxCooldowns()) return done(HomeResultStatus.BUSY);
            request = new Request(actor, owner, name, permission, config, !respawn);
            requests.put(actor, request);
            lookup = expected == null ? repository.findByName(owner, name) : repository.findMatching(expected);
        }
        var deadline = new CompletableFuture<Void>();
        deadline.orTimeout((long) warmup + config.runtime().timeoutSeconds(), TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            if (error != null) {
                Phase prior = request.phase.getAndSet(Phase.DONE);
                if (prior != Phase.DONE) {
                    if (prior == Phase.DISPATCHED) plugin.getSLF4JLogger().warn("Home teleport outcome unknown: player {}, owner {}, home {}",
                        request.actor, request.owner, request.name);
                    finish(request, prior == Phase.DISPATCHED ? HomeResultStatus.OUTCOME_UNKNOWN : HomeResultStatus.TIMED_OUT);
                }
            }
        });
        request.result.whenComplete((ignored, error) -> deadline.complete(null));
        // An admitted lookup retains only immutable request data and weak session handles on the I/O lane.
        var playerHandle = new WeakReference<>(player);
        var requestHandle = new WeakReference<>(request);
        lookup.whenComplete((home, error) -> {
            Request current = requestHandle.get();
            if (current == null || !preparing(current)) return;
            if (error != null) { cancel(current, storageFailure("lookup", error)); return; }
            if (home.isEmpty()) {
                cancel(current, expected == null ? HomeResultStatus.HOME_NOT_FOUND : HomeResultStatus.HOME_CHANGED);
                return;
            }
            Player online = playerHandle.get();
            if (online == null) { cancel(current, HomeResultStatus.CANCELLED); return; }
            onOwner(current, online, () -> warmup(current, online, home.get(), warmup), respawn ? 1 : 0);
        });
        return request.result;
    }

    private void warmup(Request request, Player player, Home home, int seconds) {
        if (seconds == 0) { inspect(request, player, home); return; }
        var messages = request.config.messages();
        String template = messages.warmupStart();
        player.sendMessage(MessageFormatter.parse(MINI, template.contains("<prefix>") ? template : messages.prefix() + template,
            Placeholder.parsed("prefix", messages.prefix()),
            Placeholder.unparsed("home", home.name()), Placeholder.unparsed("seconds", Integer.toString(seconds))));
        onOwner(request, player, () -> inspect(request, player, home), seconds * 20L);
    }

    private void inspect(Request request, Player player, Home home) {
        if (!authorized(request, player)) return;
        var world = ConcurrentTeleportPipelineEngine.resolve(home, request.config);
        if (world == null) { cancel(request, HomeResultStatus.WORLD_UNAVAILABLE); return; }
        if (restricted(world.getName(), request.config)) { cancel(request, HomeResultStatus.WORLD_RESTRICTED); return; }
        var target = ConcurrentTeleportPipelineEngine.position(home, world);
        var inspection = engine.inspect(target, request.config);
        request.observation = inspection;
        if (!preparing(request)) inspection.complete(HomeResultStatus.CANCELLED);
        inspection.whenComplete((status, error) -> {
            request.observation = null;
            if (!preparing(request)) return;
            if (error != null) {
                plugin.getSLF4JLogger().warn("Home landing inspection failed", error);
                cancel(request, HomeResultStatus.TELEPORT_FAILED);
            } else if (status != HomeResultStatus.SUCCESS) cancel(request, status);
            else {
                long checked = System.nanoTime();
                onOwner(request, player, () -> {
                    if (System.nanoTime() - checked > TimeUnit.MILLISECONDS.toNanos(request.config.runtime().safetyFreshnessMillis())) {
                        cancel(request, HomeResultStatus.UNSAFE_LOCATION);
                        return;
                    }
                    if (ConcurrentTeleportPipelineEngine.resolve(home, request.config) != world) {
                        cancel(request, HomeResultStatus.WORLD_UNAVAILABLE);
                        return;
                    }
                    dispatch(request, player, target);
                }, 0);
            }
        });
    }

    private void dispatch(Request request, Player player, Location target) {
        if (!authorized(request, player)) return;
        boolean applyCooldown = request.trackCooldown && !player.hasPermission(Permissions.HOME_BYPASS_COOLDOWN);
        if (applyCooldown && remainingCooldown(request.actor) > 0) {
            cancel(request, HomeResultStatus.COOLDOWN_ACTIVE);
            return;
        }
        UUID ticket = engine.reserveDispatch(request.actor);
        if (ticket == null) { cancel(request, HomeResultStatus.BUSY); return; }
        var outcome = new CompletableFuture<Boolean>();
        boolean claimed;
        synchronized (admission) {
            claimed = !closed && settings.get() == request.config && request.phase.compareAndSet(Phase.PREPARING, Phase.DISPATCHED);
            if (claimed) request.observation = outcome;
        }
        if (!claimed) {
            engine.releaseDispatch(request.actor, ticket);
            cancel(request, HomeResultStatus.CANCELLED);
            return;
        }
        outcome.whenComplete((success, failure) -> {
            if (!request.phase.compareAndSet(Phase.DISPATCHED, Phase.DONE)) return;
            if (failure != null) plugin.getSLF4JLogger().warn("Home teleport dispatch failed", failure);
            if (Boolean.TRUE.equals(success) && applyCooldown) {
                synchronized (admission) {
                    if (!closed && cooldowns.size() < request.config.runtime().maxCooldowns() && request.config.cooldownSeconds() > 0)
                        cooldowns.put(request.actor, System.nanoTime() + TimeUnit.SECONDS.toNanos(request.config.cooldownSeconds()));
                }
            }
            finish(request, Boolean.TRUE.equals(success) ? HomeResultStatus.SUCCESS : HomeResultStatus.TELEPORT_FAILED);
        });
        if (request.phase.get() == Phase.DISPATCHED) engine.teleport(player, target, request.actor, ticket, outcome);
        else engine.releaseDispatch(request.actor, ticket);
    }

    private boolean authorized(Request request, Player player) {
        if (!preparing(request)) return false;
        if (request.permission != null && !player.hasPermission(request.permission)) {
            cancel(request, HomeResultStatus.NO_PERMISSION); return false;
        }
        return true;
    }

    private boolean preparing(Request request) {
        if (request.phase.get() != Phase.PREPARING) return false;
        if (closed || settings.get() != request.config) {
            cancel(request, HomeResultStatus.CANCELLED); return false;
        }
        return true;
    }

    private void onOwner(Request request, Player player, Runnable action, long delay) {
        if (!preparing(request)) return;
        try {
            var scheduler = player.getScheduler();
            Runnable retired = () -> cancel(request, HomeResultStatus.CANCELLED);
            Consumer<ScheduledTask> callback = ignored -> {
                if (preparing(request)) {
                    try { action.run(); }
                    catch (RuntimeException error) {
                        plugin.getSLF4JLogger().warn("Home entity handoff failed", error);
                        cancel(request, HomeResultStatus.TELEPORT_FAILED);
                    }
                }
            };
            ScheduledTask task = delay > 0 ? scheduler.runDelayed(plugin, callback, retired, delay) : scheduler.run(plugin, callback, retired);
            if (task == null) cancel(request, HomeResultStatus.CANCELLED);
            else if (delay > 0) {
                request.warmup = task;
                if (request.phase.get() != Phase.PREPARING) task.cancel();
            }
        } catch (RuntimeException error) { cancel(request, HomeResultStatus.CANCELLED); }
    }

    public void cancel(UUID player, HomeResultStatus status) {
        Request request = requests.get(player);
        if (request != null) cancel(request, status);
    }

    public boolean hasPending(UUID player) {
        Request request = requests.get(player);
        return request != null && request.phase.get() == Phase.PREPARING;
    }

    private void cancel(Request request, HomeResultStatus status) {
        if (request.phase.compareAndSet(Phase.PREPARING, Phase.DONE)) finish(request, status);
    }

    private void finish(Request request, HomeResultStatus status) {
        CompletableFuture<?> observation = request.observation;
        request.observation = null;
        if (observation != null) observation.cancel(false);
        ScheduledTask task = request.warmup;
        request.warmup = null;
        try {
            if (task != null) task.cancel();
        } finally {
            requests.remove(request.actor, request);
            request.result.complete(status);
        }
    }

    @Override public long remainingCooldown(UUID player) {
        synchronized (admission) {
            Long expiry = cooldowns.get(player);
            if (expiry == null) return 0;
            long remaining = expiry - System.nanoTime();
            if (remaining <= 0) { cooldowns.remove(player); return 0; }
            return TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1;
        }
    }

    public CompletableFuture<Void> close() {
        synchronized (admission) { closed = true; cooldowns.clear(); }
        requests.forEach((id, request) -> {
            Phase prior = request.phase.getAndSet(Phase.DONE);
            if (prior != Phase.DONE) finish(request, prior == Phase.DISPATCHED ? HomeResultStatus.OUTCOME_UNKNOWN : HomeResultStatus.CANCELLED);
        });
        return repository.close();
    }

    public static boolean valid(String name) { return name != null && NAME.matcher(name).matches(); }

    public static int homeLimit(Player player, HomeConfig config) {
        if (player.hasPermission(Permissions.HOME_BYPASS_LIMIT)) return Integer.MAX_VALUE;
        int limit = config.homeLimits().getOrDefault("default", 3);
        String prefix = "tpcore.homes.limit.";
        for (var entry : config.homeLimits().entrySet()) {
            if (player.hasPermission(prefix + entry.getKey())) limit = Math.max(limit, entry.getValue());
        }
        for (var permission : player.getEffectivePermissions()) {
            String node = permission.getPermission();
            if (permission.getValue() && node.startsWith(prefix)) {
                try { limit = Math.max(limit, Integer.parseInt(node.substring(prefix.length()))); }
                catch (NumberFormatException ignored) { /* Named tiers are handled above. */ }
            }
        }
        return Math.max(0, limit);
    }

    public static boolean restricted(String world, HomeConfig config) {
        var rule = config.worldRestrictions();
        if (rule == null || rule.worlds() == null) return false;
        boolean listed = rule.worlds().stream().anyMatch(world::equalsIgnoreCase);
        return "WHITELIST".equalsIgnoreCase(rule.mode()) ? !listed : listed;
    }

    public HomeResultStatus storageFailure(String operation, Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof HomeUnavailable unavailable) return unavailable.status;
        if (failure instanceof RejectedExecutionException) return closed ? HomeResultStatus.DISABLED : HomeResultStatus.BUSY;
        plugin.getSLF4JLogger().warn("Home storage operation failed: {}", operation, failure);
        return HomeResultStatus.STORAGE_ERROR;
    }

    private static CompletableFuture<HomeResultStatus> done(HomeResultStatus status) { return CompletableFuture.completedFuture(status); }
    private static final class HomeUnavailable extends RuntimeException {
        final HomeResultStatus status;
        HomeUnavailable(HomeResultStatus status) { this.status = status; }
    }
}
