package com.lunatech.tpcore.module.pwarp.service.impl;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.pwarp.cache.PwarpCache;
import com.lunatech.tpcore.module.pwarp.config.PwarpConfig;
import com.lunatech.tpcore.module.pwarp.config.PwarpWorldConfig;
import com.lunatech.tpcore.module.pwarp.economy.PwarpEconomyService;
import com.lunatech.tpcore.module.pwarp.economy.impl.VaultPwarpEconomyService;
import com.lunatech.tpcore.module.pwarp.model.Pwarp;
import com.lunatech.tpcore.module.pwarp.model.PwarpRating;
import com.lunatech.tpcore.module.pwarp.model.PwarpSorting;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRatingRepository;
import com.lunatech.tpcore.module.pwarp.repository.PwarpRepository;
import com.lunatech.tpcore.module.pwarp.repository.impl.SqlitePwarpRatingRepository;
import com.lunatech.tpcore.module.pwarp.service.PwarpService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultPwarpService implements PwarpService {

    private final JavaPlugin plugin;
    private final Supplier<PwarpConfig> configSupplier;
    private final PwarpRepository repository;
    private final PwarpRatingRepository ratingRepository;
    private final PwarpCache cache;
    private final PwarpEconomyService economyService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private record WarmupSession(ScheduledTask task, CompletableFuture<Boolean> future, String targetWorldName) {}

    private final Map<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
    private final Map<UUID, WarmupSession> warmupTasks = new ConcurrentHashMap<>();

    public DefaultPwarpService(
        JavaPlugin plugin,
        Supplier<PwarpConfig> configSupplier,
        PwarpRepository repository,
        PwarpCache cache
    ) {
        this(
            plugin,
            configSupplier,
            repository,
            new SqlitePwarpRatingRepository(plugin.getDataFolder(), plugin.getSLF4JLogger()),
            cache,
            new VaultPwarpEconomyService(plugin.getSLF4JLogger())
        );
    }

    public DefaultPwarpService(
        JavaPlugin plugin,
        Supplier<PwarpConfig> configSupplier,
        PwarpRepository repository,
        PwarpRatingRepository ratingRepository,
        PwarpCache cache
    ) {
        this(
            plugin,
            configSupplier,
            repository,
            ratingRepository,
            cache,
            new VaultPwarpEconomyService(plugin.getSLF4JLogger())
        );
    }

    public DefaultPwarpService(
        JavaPlugin plugin,
        Supplier<PwarpConfig> configSupplier,
        PwarpRepository repository,
        PwarpRatingRepository ratingRepository,
        PwarpCache cache,
        PwarpEconomyService economyService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier cannot be null");
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.ratingRepository = Objects.requireNonNull(ratingRepository, "ratingRepository cannot be null");
        this.cache = Objects.requireNonNull(cache, "cache cannot be null");
        this.economyService = Objects.requireNonNull(economyService, "economyService cannot be null");
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return this.repository.initialize()
            .thenCompose(v -> this.ratingRepository.initialize())
            .thenCompose(v -> this.repository.loadAllPwarps())
            .thenCompose(warps -> this.ratingRepository.loadAllRatings().thenAccept(ratings -> {
                this.cache.clear();

                Map<Integer, List<PwarpRating>> ratingGroupMap = new ConcurrentHashMap<>();
                for (PwarpRating rating : ratings) {
                    ratingGroupMap.computeIfAbsent(rating.warpId(), k -> new ArrayList<>()).add(rating);
                }

                for (Pwarp pwarp : warps) {
                    List<PwarpRating> warpRatings = ratingGroupMap.getOrDefault(pwarp.id(), List.of());
                    double avg = 0.0;
                    if (!warpRatings.isEmpty()) {
                        double sum = 0.0;
                        for (PwarpRating r : warpRatings) {
                            sum += r.stars();
                        }
                        avg = sum / warpRatings.size();
                    }

                    Pwarp updated = new Pwarp(
                        pwarp.id(),
                        pwarp.ownerUuid(),
                        pwarp.ownerName(),
                        pwarp.name(),
                        pwarp.description(),
                        pwarp.worldName(),
                        pwarp.x(),
                        pwarp.y(),
                        pwarp.z(),
                        pwarp.yaw(),
                        pwarp.pitch(),
                        pwarp.iconMaterial(),
                        pwarp.category(),
                        pwarp.isPrivate(),
                        pwarp.createdAt(),
                        pwarp.visits(),
                        avg,
                        warpRatings.size(),
                        pwarp.price(),
                        pwarp.bank()
                    );
                    this.cache.put(updated);
                }
                this.plugin.getSLF4JLogger().info("Loaded {} player warps and {} ratings into L1 cache.", warps.size(), ratings.size());
            }));
    }

    @Override
    public CompletableFuture<Boolean> setWarp(Player player, String name, String description) {
        return setWarp(player, name, "general", description);
    }

    @Override
    public CompletableFuture<Boolean> setWarp(Player player, String name, String category, String description) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        if (!config.enabled()) {
            sendMessage(player, config.messages().disabled());
            return CompletableFuture.completedFuture(false);
        }

        if (!player.hasPermission(Permissions.PWARP_SET)) {
            sendMessage(player, config.messages().noPermission());
            return CompletableFuture.completedFuture(false);
        }

        World world = player.getWorld();
        PwarpWorldConfig worldConfig = config.worldConfigs().get(world.getName().toLowerCase(Locale.ROOT));
        if (worldConfig != null && !worldConfig.enabled()) {
            sendMessage(player, config.messages().worldDisabled(), Placeholder.unparsed("world", world.getName()));
            return CompletableFuture.completedFuture(false);
        }

        String warpName = name != null ? name.trim() : "";
        if (warpName.isBlank() || warpName.length() > config.maxNameLength()) {
            sendMessage(player, config.messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        if (this.cache.getByName(warpName).isPresent()) {
            sendMessage(player, config.messages().warpExists(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        int currentCount = this.cache.countByOwner(player.getUniqueId());
        int maxLimit = getMaxWarpLimit(player);
        if (currentCount >= maxLimit) {
            sendMessage(player, config.messages().limitReached(), Placeholder.unparsed("max", String.valueOf(maxLimit)));
            return CompletableFuture.completedFuture(false);
        }

        double creationFee = config.creationFee();
        if (creationFee > 0.0 && !player.hasPermission(Permissions.PWARP_ADMIN)) {
            if (!this.economyService.has(player, creationFee)) {
                sendMessage(player, config.messages().insufficientFunds(),
                    Placeholder.parsed("price", this.economyService.format(creationFee))
                );
                return CompletableFuture.completedFuture(false);
            }
            if (!this.economyService.withdraw(player, creationFee)) {
                sendMessage(player, config.messages().insufficientFunds(),
                    Placeholder.parsed("price", this.economyService.format(creationFee))
                );
                return CompletableFuture.completedFuture(false);
            }
            sendMessage(player, config.messages().creationFeeCharged(),
                Placeholder.parsed("fee", this.economyService.format(creationFee)),
                Placeholder.parsed("warp", warpName)
            );
        }

        Location loc = player.getLocation();
        Pwarp newWarp = new Pwarp(
            0,
            player.getUniqueId(),
            player.getName(),
            warpName,
            description != null ? description.trim() : "",
            world.getName(),
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            loc.getYaw(),
            loc.getPitch(),
            config.defaultIconMaterial(),
            category != null ? category : "general",
            false,
            System.currentTimeMillis(),
            0,
            0.0,
            0,
            0.0,
            0.0
        );

        this.cache.put(newWarp);
        return this.repository.savePwarp(newWarp).thenApply(v -> {
            sendMessage(player, config.messages().setSuccess(), Placeholder.unparsed("warp", warpName));
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteWarp(Player player, String name) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        String warpName = name != null ? name.trim() : "";
        Optional<Pwarp> existing = this.cache.getByName(warpName);

        if (existing.isEmpty()) {
            sendMessage(player, config.messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        Pwarp pwarp = existing.get();
        boolean isOwner = pwarp.ownerUuid().equals(player.getUniqueId());
        boolean hasAdmin = player.hasPermission(Permissions.PWARP_ADMIN);

        if (!isOwner && !hasAdmin) {
            sendMessage(player, config.messages().noPermission());
            return CompletableFuture.completedFuture(false);
        }

        this.cache.remove(warpName);
        return this.repository.deletePwarp(warpName).thenApply(v -> {
            sendMessage(player, config.messages().delSuccess(), Placeholder.unparsed("warp", warpName));
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> executeTeleport(Player player, String name) {
        String warpName = name != null ? name.trim() : "";
        Optional<Pwarp> pwarp = getWarp(warpName);

        if (pwarp.isEmpty()) {
            sendMessage(player, this.configSupplier.get().messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }
        return executeTeleport(player, pwarp.get());
    }

    @Override
    public CompletableFuture<Boolean> executeTeleport(Player player, Pwarp pwarp) {
        if (player == null || !player.isOnline() || pwarp == null) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        if (!config.enabled()) {
            sendMessage(player, config.messages().disabled());
            return CompletableFuture.completedFuture(false);
        }

        if (!player.hasPermission(Permissions.PWARP_USE)) {
            sendMessage(player, config.messages().noPermission());
            return CompletableFuture.completedFuture(false);
        }

        if (player.isInsideVehicle()) {
            sendMessage(player, config.messages().cannotUseMounted());
            return CompletableFuture.completedFuture(false);
        }

        World world = this.plugin.getServer().getWorld(pwarp.worldName());
        if (world == null) {
            sendMessage(player, config.messages().worldNotFound(), Placeholder.unparsed("world", pwarp.worldName()));
            return CompletableFuture.completedFuture(false);
        }

        PwarpWorldConfig worldConfig = config.worldConfigs().get(world.getName().toLowerCase(Locale.ROOT));
        if (worldConfig != null && !worldConfig.enabled()) {
            sendMessage(player, config.messages().worldDisabled(), Placeholder.unparsed("world", world.getName()));
            return CompletableFuture.completedFuture(false);
        }

        boolean isOwner = pwarp.ownerUuid().equals(player.getUniqueId());
        boolean bypassPrice = player.hasPermission(Permissions.PWARP_BYPASS_PRICE) || player.hasPermission(Permissions.PWARP_ADMIN);

        if (pwarp.price() > 0.0 && !isOwner && !bypassPrice) {
            if (!this.economyService.has(player, pwarp.price())) {
                sendMessage(player, config.messages().insufficientFunds(),
                    Placeholder.parsed("price", this.economyService.format(pwarp.price()))
                );
                return CompletableFuture.completedFuture(false);
            }
            if (!this.economyService.withdraw(player, pwarp.price())) {
                sendMessage(player, config.messages().insufficientFunds(),
                    Placeholder.parsed("price", this.economyService.format(pwarp.price()))
                );
                return CompletableFuture.completedFuture(false);
            }
            double newBank = pwarp.bank() + pwarp.price();
            Pwarp updated = new Pwarp(
                pwarp.id(), pwarp.ownerUuid(), pwarp.ownerName(), pwarp.name(),
                pwarp.description(), pwarp.worldName(), pwarp.x(), pwarp.y(), pwarp.z(),
                pwarp.yaw(), pwarp.pitch(), pwarp.iconMaterial(), pwarp.category(),
                pwarp.isPrivate(), pwarp.createdAt(), pwarp.visits(), pwarp.averageRating(),
                pwarp.totalRatings(), pwarp.price(), newBank
            );
            this.cache.put(updated);
            this.repository.updateBank(pwarp.name(), newBank);

            sendMessage(player, config.messages().teleportFeeCharged(),
                Placeholder.parsed("price", this.economyService.format(pwarp.price())),
                Placeholder.parsed("warp", pwarp.name())
            );
        }

        long now = System.currentTimeMillis();
        long cooldownSec = worldConfig != null ? worldConfig.cooldownSeconds() : 0;
        boolean bypassCooldown = player.hasPermission(Permissions.PWARP_BYPASS_COOLDOWN) || player.hasPermission(Permissions.PWARP_ADMIN);

        if (cooldownSec > 0 && !bypassCooldown) {
            Long lastUse = this.cooldownMap.get(player.getUniqueId());
            if (lastUse != null) {
                long elapsedSec = (now - lastUse) / 1000;
                if (elapsedSec < cooldownSec) {
                    long remaining = cooldownSec - elapsedSec;
                    sendMessage(player, config.messages().cooldownActive(), Placeholder.unparsed("seconds", String.valueOf(remaining)));
                    return CompletableFuture.completedFuture(false);
                }
            }
        }

        long warmupSec = worldConfig != null ? worldConfig.warmupSeconds() : 0;
        boolean bypassWarmup = player.hasPermission(Permissions.PWARP_BYPASS_WARMUP) || player.hasPermission(Permissions.PWARP_ADMIN);

        Location targetLoc = new Location(world, pwarp.x(), pwarp.y(), pwarp.z(), pwarp.yaw(), pwarp.pitch());

        if (warmupSec <= 0 || bypassWarmup) {
            return performTeleport(player, targetLoc, pwarp.name(), cooldownSec);
        }

        cancelWarmup(player.getUniqueId());
        sendMessage(player, config.messages().warmupStarted(), Placeholder.unparsed("warp", pwarp.name()), Placeholder.unparsed("seconds", String.valueOf(warmupSec)));

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ScheduledTask task = player.getScheduler().runDelayed(
            this.plugin,
            scheduledTask -> {
                this.warmupTasks.remove(player.getUniqueId());
                performTeleport(player, targetLoc, pwarp.name(), cooldownSec).whenComplete((res, ex) -> {
                    if (ex != null) {
                        future.completeExceptionally(ex);
                    } else {
                        future.complete(res);
                    }
                });
            },
            () -> {
                this.warmupTasks.remove(player.getUniqueId());
                sendMessage(player, config.messages().warmupCancelled());
                future.complete(false);
            },
            warmupSec * 20L
        );

        if (task != null) {
            this.warmupTasks.put(player.getUniqueId(), new WarmupSession(task, future, world.getName()));
        } else {
            future.complete(false);
        }

        return future;
    }

    @Override
    public CompletableFuture<Boolean> rateWarp(Player player, String name, int stars) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        int cleanStars = Math.max(1, Math.min(5, stars));
        String warpName = name != null ? name.trim() : "";
        Optional<Pwarp> pwarpOpt = getWarp(warpName);

        if (pwarpOpt.isEmpty()) {
            sendMessage(player, this.configSupplier.get().messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        Pwarp pwarp = pwarpOpt.get();
        UUID playerUuid = player.getUniqueId();

        return this.ratingRepository.getRating(pwarp.id(), playerUuid).thenCompose(existingOpt -> {
            return this.ratingRepository.saveRating(pwarp.id(), playerUuid, cleanStars).thenCompose(v -> {
                return this.ratingRepository.getRatingsForWarp(pwarp.id()).thenApply(ratings -> {
                    double sum = 0.0;
                    for (PwarpRating r : ratings) {
                        sum += r.stars();
                    }
                    double avg = ratings.isEmpty() ? 0.0 : sum / ratings.size();

                    Pwarp updated = new Pwarp(
                        pwarp.id(),
                        pwarp.ownerUuid(),
                        pwarp.ownerName(),
                        pwarp.name(),
                        pwarp.description(),
                        pwarp.worldName(),
                        pwarp.x(),
                        pwarp.y(),
                        pwarp.z(),
                        pwarp.yaw(),
                        pwarp.pitch(),
                        pwarp.iconMaterial(),
                        pwarp.category(),
                        pwarp.isPrivate(),
                        pwarp.createdAt(),
                        pwarp.visits(),
                        avg,
                        ratings.size(),
                        pwarp.price(),
                        pwarp.bank()
                    );
                    this.cache.put(updated);
                    return true;
                });
            });
        });
    }

    @Override
    public CompletableFuture<Boolean> setWarpPrice(Player player, String name, double price) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        String warpName = name != null ? name.trim() : "";
        Optional<Pwarp> existing = this.cache.getByName(warpName);

        if (existing.isEmpty()) {
            sendMessage(player, config.messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        Pwarp warp = existing.get();
        boolean isOwner = warp.ownerUuid().equals(player.getUniqueId());
        boolean hasAdmin = player.hasPermission(Permissions.PWARP_ADMIN);

        if (!isOwner && !hasAdmin) {
            sendMessage(player, config.messages().notOwner(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        double cleanPrice = Math.max(0.0, price);
        Pwarp updated = new Pwarp(
            warp.id(), warp.ownerUuid(), warp.ownerName(), warp.name(),
            warp.description(), warp.worldName(), warp.x(), warp.y(), warp.z(),
            warp.yaw(), warp.pitch(), warp.iconMaterial(), warp.category(),
            warp.isPrivate(), warp.createdAt(), warp.visits(), warp.averageRating(),
            warp.totalRatings(), cleanPrice, warp.bank()
        );
        this.cache.put(updated);

        return this.repository.updatePrice(warp.name(), cleanPrice).thenApply(v -> {
            sendMessage(player, config.messages().priceSetSuccess(),
                Placeholder.unparsed("warp", warp.name()),
                Placeholder.parsed("price", this.economyService.format(cleanPrice))
            );
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> withdrawBank(Player player, String name, double amount) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        PwarpConfig config = this.configSupplier.get();
        String warpName = name != null ? name.trim() : "";
        Optional<Pwarp> existing = this.cache.getByName(warpName);

        if (existing.isEmpty()) {
            sendMessage(player, config.messages().warpNotFound(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        Pwarp warp = existing.get();
        boolean isOwner = warp.ownerUuid().equals(player.getUniqueId());
        boolean hasAdmin = player.hasPermission(Permissions.PWARP_ADMIN);

        if (!isOwner && !hasAdmin) {
            sendMessage(player, config.messages().notOwner(), Placeholder.unparsed("warp", warpName));
            return CompletableFuture.completedFuture(false);
        }

        if (warp.bank() <= 0.0) {
            sendMessage(player, config.messages().bankWithdrawEmpty(), Placeholder.unparsed("warp", warp.name()));
            return CompletableFuture.completedFuture(false);
        }

        double withdrawAmount = (amount <= 0.0 || amount > warp.bank()) ? warp.bank() : amount;
        double newBank = warp.bank() - withdrawAmount;

        boolean depositSuccess = this.economyService.deposit(player, withdrawAmount);
        if (!depositSuccess) {
            sendMessage(player, config.messages().teleportFailed());
            return CompletableFuture.completedFuture(false);
        }

        Pwarp updated = new Pwarp(
            warp.id(), warp.ownerUuid(), warp.ownerName(), warp.name(),
            warp.description(), warp.worldName(), warp.x(), warp.y(), warp.z(),
            warp.yaw(), warp.pitch(), warp.iconMaterial(), warp.category(),
            warp.isPrivate(), warp.createdAt(), warp.visits(), warp.averageRating(),
            warp.totalRatings(), warp.price(), newBank
        );
        this.cache.put(updated);

        return this.repository.updateBank(warp.name(), newBank).thenApply(v -> {
            sendMessage(player, config.messages().bankWithdrawSuccess(),
                Placeholder.unparsed("warp", warp.name()),
                Placeholder.parsed("amount", this.economyService.format(withdrawAmount))
            );
            return true;
        });
    }

    private CompletableFuture<Boolean> performTeleport(Player player, Location targetLoc, String warpName, long cooldownSec) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        player.getScheduler().run(this.plugin, task -> {
            try {
                boolean success = player.teleport(targetLoc, TeleportCause.PLUGIN);
                if (success) {
                    if (cooldownSec > 0) {
                        this.cooldownMap.put(player.getUniqueId(), System.currentTimeMillis());
                    }
                    getWarp(warpName).ifPresent(w -> {
                        Pwarp updatedVisits = new Pwarp(
                            w.id(), w.ownerUuid(), w.ownerName(), w.name(), w.description(),
                            w.worldName(), w.x(), w.y(), w.z(), w.yaw(), w.pitch(),
                            w.iconMaterial(), w.category(), w.isPrivate(), w.createdAt(),
                            w.visits() + 1, w.averageRating(), w.totalRatings(), w.price(), w.bank()
                        );
                        this.cache.put(updatedVisits);
                    });
                    this.repository.incrementVisits(warpName);
                    sendMessage(player, this.configSupplier.get().messages().teleportSuccess(), Placeholder.unparsed("warp", warpName));
                    future.complete(true);
                } else {
                    sendMessage(player, this.configSupplier.get().messages().teleportFailed());
                    future.complete(false);
                }
            } catch (Exception e) {
                this.plugin.getSLF4JLogger().error("Failed to teleport player {} to pwarp {}", player.getName(), warpName, e);
                future.complete(false);
            }
        }, null);
        return future;
    }

    @Override
    public Optional<Pwarp> getWarp(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return this.cache.getByName(name.trim());
    }

    @Override
    public List<Pwarp> getPublicWarps() {
        return this.cache.getAllPublic();
    }

    @Override
    public List<Pwarp> getPublicWarps(PwarpSorting sorting, String categoryFilter) {
        return this.cache.getSortedPublicWarps(sorting, categoryFilter);
    }

    @Override
    public List<Pwarp> getPlayerWarps(UUID ownerUuid) {
        if (ownerUuid == null) {
            return List.of();
        }
        return this.cache.getByOwner(ownerUuid);
    }

    @Override
    public int getMaxWarpLimit(Player player) {
        if (player == null) {
            return 0;
        }
        if (player.hasPermission(Permissions.PWARP_ADMIN)) {
            return 999;
        }

        PwarpConfig config = this.configSupplier.get();
        int highestLimit = config.warpLimits().getOrDefault("default", 2);

        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            String perm = pai.getPermission().toLowerCase(Locale.ROOT);
            if (perm.startsWith("tpcore.pwarp.limit.") && pai.getValue()) {
                String key = perm.substring("tpcore.pwarp.limit.".length());
                Integer limit = config.warpLimits().get(key);
                if (limit != null && limit > highestLimit) {
                    highestLimit = limit;
                }
            }
        }
        return highestLimit;
    }

    @Override
    public long getRemainingCooldownSeconds(UUID playerUniqueId) {
        if (playerUniqueId == null) {
            return 0;
        }
        Long lastUse = this.cooldownMap.get(playerUniqueId);
        if (lastUse == null) {
            return 0;
        }
        long elapsedSec = (System.currentTimeMillis() - lastUse) / 1000;
        long cooldownSec = this.configSupplier.get().worldConfigs().values().stream()
            .mapToLong(PwarpWorldConfig::cooldownSeconds)
            .max().orElse(0);

        return Math.max(0, cooldownSec - elapsedSec);
    }

    @Override
    public boolean isWarmingUp(UUID playerUniqueId) {
        if (playerUniqueId == null) {
            return false;
        }
        return this.warmupTasks.containsKey(playerUniqueId);
    }

    @Override
    public boolean hasActiveWarmups() {
        return !this.warmupTasks.isEmpty();
    }

    @Override
    public void cancelWarmup(UUID playerUniqueId) {
        if (playerUniqueId == null) {
            return;
        }
        WarmupSession session = this.warmupTasks.remove(playerUniqueId);
        if (session != null && session.task() != null) {
            session.task().cancel();
            session.future().complete(false);
        }
    }

    @Override
    public void cancelWarmupsForWorld(String worldName) {
        if (worldName == null || worldName.isBlank() || this.warmupTasks.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, WarmupSession> entry : new ArrayList<>(this.warmupTasks.entrySet())) {
            WarmupSession session = entry.getValue();
            if (session.targetWorldName() != null && session.targetWorldName().equalsIgnoreCase(worldName)) {
                Player player = this.plugin.getServer().getPlayer(entry.getKey());
                cancelWarmup(entry.getKey());
                if (player != null && player.isOnline()) {
                    sendMessage(player, this.configSupplier.get().messages().warmupCancelled());
                }
            }
        }
    }

    @Override
    public void shutdown() {
        for (UUID uuid : new ArrayList<>(this.warmupTasks.keySet())) {
            cancelWarmup(uuid);
        }
        this.cooldownMap.clear();
        this.ratingRepository.close();
    }

    @Override
    public void updateConfig(PwarpConfig newConfig) {
        // Dynamic reload hook if needed
    }

    private void sendMessage(Player player, String messageFormat, TagResolver... resolvers) {
        if (player == null || !player.isOnline() || messageFormat == null || messageFormat.isBlank()) {
            return;
        }
        String fullMsg = this.configSupplier.get().messages().prefix() + messageFormat;
        Component component = this.miniMessage.deserialize(fullMsg, resolvers);
        player.sendMessage(component);
    }
}
