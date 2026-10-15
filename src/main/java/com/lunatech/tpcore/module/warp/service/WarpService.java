package com.lunatech.tpcore.module.warp.service;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.WarpEdit;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.model.WarpImportPlan;
import com.lunatech.tpcore.module.warp.model.WarpDiagnostics;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public interface WarpService {
    void recordCombat(UUID playerId);
    WarpResultStatus checkCreation(Player player);

    CompletableFuture<Void> initialize();
    /** Caller enforces staff permissions. Free edits preserve unrelated fields and cancel pending teleports before dispatch. */
    CompletableFuture<WarpResultStatus> editWarp(String name, WarpEdit edit);
    boolean isReady();
    WarpDiagnostics diagnostics();
    CompletableFuture<WarpDiagnostics.PaymentPage> paymentIntents(int page, int pageSize);
    CompletableFuture<Void> acknowledgePayment(String ticket, UUID actor, String note);
    CompletableFuture<Void> resumePayments();

    CompletableFuture<WarpResultStatus> teleportToWarp(Player player, String warpName, String rawPassword);

    CompletableFuture<WarpResultStatus> teleportOtherToWarp(Player sender, Player target, String warpName);

    CompletableFuture<WarpResultStatus> setWarp(Player creator, String warpName, boolean overwrite, String rawPassword, String category);

    /** Trusted internal API: archives directly. Interactive callers must use prepare/confirmDeletion. */
    CompletableFuture<WarpResultStatus> deleteWarp(String warpName);
    WarpResultStatus prepareDeletion(UUID actor, String warpName);
    CompletableFuture<WarpResultStatus> confirmDeletion(UUID actor, String warpName);
    CompletableFuture<List<DeletedWarp>> getDeletedWarps();
    CompletableFuture<WarpResultStatus> restoreWarp(UUID id);

    Optional<Warp> getWarp(String warpName);

    Collection<Warp> getAllWarps();

    List<Warp> getWarpsByCategory(String category);

    Set<String> getCategories();

    void cancelWarmupOnMove(Player player);
    void cancelWarmupOnTeleport(Player player);

    void cancelWarmupOnDamage(Player player);

    void cancelWarmupOnQuit(UUID playerUuid);

    long getRemainingCooldownSeconds(UUID playerUuid);

    void updateConfig(WarpConfig newConfig);

    CompletableFuture<Integer> migrateData(String fromStorage, String toStorage);
    CompletableFuture<WarpImportPlan> previewEssentialsImport(UUID actor, String policy, Map<String, WarpImportPlan.WorldData> worlds);
    CompletableFuture<WarpImportPlan.Result> confirmEssentialsImport(UUID actor, UUID token, Map<String, WarpImportPlan.WorldData> worlds);

    CompletableFuture<Void> close();
}
