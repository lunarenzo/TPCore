package com.lunatech.tpcore.module.warp;

import com.lunatech.tpcore.config.model.WarpConfig;
import com.lunatech.tpcore.config.model.WarpImportConfig;
import com.lunatech.tpcore.module.warp.cache.impl.DefaultWarpCache;
import com.lunatech.tpcore.module.warp.economy.WarpEconomyService;
import com.lunatech.tpcore.module.warp.model.Warp;
import com.lunatech.tpcore.module.warp.model.DeletedWarp;
import com.lunatech.tpcore.module.warp.model.WarpImportPlan;
import com.lunatech.tpcore.module.warp.repository.WarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.SqliteWarpRepository;
import com.lunatech.tpcore.module.warp.repository.impl.YamlWarpRepository;
import com.lunatech.tpcore.module.warp.service.impl.DefaultWarpService;
import com.lunatech.tpcore.module.warp.service.impl.EssentialsWarpImport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EssentialsWarpImportTest {
    @TempDir Path root;
    private final UUID worldId = UUID.fromString("a1111111-1111-1111-1111-111111111111");
    private final EssentialsWarpImport importer = new EssentialsWarpImport();
    private Map<String, WarpImportPlan.WorldData> worlds() {
        var world = new WarpImportPlan.WorldData(worldId, "world", -64, 320);
        return Map.of("name:world", world, "id:" + worldId, world);
    }
    private String yaml(String name) { return "name: '" + name + "'\nworld: world\nx: -5.5\ny: 64.0\nz: 10\nyaw: 90\npitch: -20\n"; }
    private Path folder() throws Exception {
        Path plugins = Files.createTempDirectory(root, "plugins-"); Files.createDirectory(plugins.resolve("TPCore")); Files.createDirectories(plugins.resolve("Essentials/warps"));
        return plugins.resolve("TPCore");
    }
    private void source(Path folder, String file, String text) throws Exception { Files.writeString(folder.getParent().resolve("Essentials/warps").resolve(file), text); }
    private Warp original() { return new Warp("hub", worldId, "world", 1, 70, 2, 3, 4, UUID.randomUUID(), "staff", "hash", true, 123, true, "Closed bridge", true); }
    private WarpConfig settings(WarpImportConfig imports) {
        WarpConfig d = WarpConfig.createDefault();
        return new WarpConfig(d.enabled(), d.warmupSeconds(), d.cooldownSeconds(), d.cancelOnMove(), d.cancelOnDamage(), d.defaultPermissionGated(),
            d.defaultCategory(), d.warpsPerPage(), d.maxWarps(), d.economyEnabled(), d.warpCost(), d.setWarpCost(), d.chargeTiming(), d.refundOnCancel(),
            d.safetyChecks(), d.storage(), d.messages(), d.maxConcurrentOperations(), d.maxCachedWarps(), d.maxPlayerStateEntries(), d.storageQueueCapacity(),
            d.operationTimeoutSeconds(), d.maxStorageBytes(), d.editMessages(), d.control(), d.controlMessages(), d.browsing(), imports);
    }
    private DefaultWarpService service(Path folder, WarpRepository repo, WarpConfig config) {
        return service(folder, repo, config, null);
    }
    private DefaultWarpService service(Path folder, WarpRepository repo, WarpConfig config, Warp initial) {
        Plugin plugin = mock(Plugin.class); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        WarpEconomyService economy = mock(WarpEconomyService.class); when(economy.close()).thenReturn(CompletableFuture.completedFuture(null));
        var cache = new DefaultWarpCache();
        var service = new DefaultWarpService(plugin, repo, cache, economy, config, mock(Logger.class)); service.initialize().join();
        if (initial != null) { repo.save(initial).join(); cache.putWarp(initial); }
        return service;
    }
    private WarpRepository repository(Path folder, boolean sql) {
        WarpConfig d = WarpConfig.createDefault();
        return sql ? new SqliteWarpRepository(folder.toFile(), mock(Logger.class), 8, d.maxCachedWarps(), d.control())
            : new YamlWarpRepository(folder.toFile(), mock(Logger.class), 8, d.maxCachedWarps(), d.maxStorageBytes(), d.control());
    }
    private WarpImportPlan plan(Path folder, Map<String, Warp> destination, String policy) {
        var config = WarpConfig.createDefault(); return importer.plan(importer.read(folder, config.essentialsImport()), destination, worlds(), config, policy);
    }
    @Test void actualEssentialsUuidAndNameFallbackPreserveLocationAndOwner() throws Exception {
        Path folder = folder(); UUID owner = UUID.randomUUID();
        source(folder, "first.yml", yaml("first").replace("world: world", "world: " + worldId.toString().toUpperCase()) + "lastowner: " + owner + "\n");
        source(folder, "second.yml", yaml("second").replace("world: world", "world: " + UUID.randomUUID() + "\nworld-name: world"));
        var plan = plan(folder, Map.of(), "skip"); assertEquals(2, plan.selected().size());
        Warp warp = plan.selected().getFirst(); assertEquals(owner, warp.creatorUuid()); assertEquals(worldId, warp.worldId());
        assertEquals(-5.5, warp.x()); assertEquals(90, warp.yaw()); assertEquals(-20, warp.pitch()); assertTrue(warp.permissionGated()); assertEquals("imported", warp.category());
    }
    @Test void invalidWorldCoordinatesNamesDuplicateNamesAndYamlObjectsAreReported() throws Exception {
        Path folder = folder(); source(folder, "1.yml", yaml("ok")); source(folder, "2.yml", yaml("OK"));
        source(folder, "3.yml", yaml("missing").replace("world: world", "world: missing"));
        source(folder, "4.yml", yaml("badcoordinate").replace("x: -5.5", "x: .nan"));
        source(folder, "5.yml", yaml("bad name")); source(folder, "6.yml", "!!java.lang.Object {}\n");
        source(folder, "7.yml", yaml("alias").replace("x: -5.5", "x: &anchor 5"));
        var plan = plan(folder, Map.of(), "skip"); assertEquals(1, plan.selected().size()); assertEquals(6, plan.invalid());
        var reasons = plan.entries().stream().map(WarpImportPlan.Entry::reason).toList();
        assertTrue(reasons.containsAll(List.of("DUPLICATE", "INVALID_WORLD", "INVALID_LOCATION", "INVALID_NAME", "INVALID_FILE")));
    }
    @Test void conflictPoliciesPreserveMetadataAndChooseUniqueDeterministicNames() throws Exception {
        Path folder = folder(); source(folder, "hub.yml", yaml("HUB")); Warp original = original();
        var destination = Map.of("hub", original);
        assertTrue(plan(folder, destination, "skip").selected().isEmpty());
        Warp replaced = plan(folder, destination, "replace").selected().getFirst();
        assertEquals(original.name(), replaced.name()); assertEquals(original.passwordHash(), replaced.passwordHash()); assertEquals(original.creatorUuid(), replaced.creatorUuid());
        assertEquals(original.createdAt(), replaced.createdAt()); assertEquals(original.category(), replaced.category()); assertTrue(replaced.closed()); assertTrue(replaced.unlisted()); assertTrue(replaced.permissionGated());
        assertEquals(-5.5, replaced.x()); assertEquals("HUB_1", plan(folder, destination, "rename").selected().getFirst().name());
    }
    @Test void fileAndByteLimitsAndOutsideSourcePathsRejectBeforeImport() throws Exception {
        Path folder = folder(); source(folder, "1.yml", yaml("one")); source(folder, "2.yml", yaml("two"));
        var d = WarpImportConfig.createDefault();
        var limited = new WarpImportConfig(d.sourceFolder(), 1, 256, 256, 10, 5, 1, "imported", true, d.messages());
        assertEquals("LIMIT", assertThrows(IllegalStateException.class, () -> importer.read(folder, limited)).getMessage());
        Files.delete(folder.getParent().resolve("Essentials/warps/2.yml")); source(folder, "1.yml", "#" + "a".repeat(300));
        assertEquals("LIMIT", assertThrows(IllegalStateException.class, () -> importer.read(folder, limited)).getMessage());
        var outside = new WarpImportConfig("../outside", 5, 256, 256, 10, 5, 1, "imported", true, d.messages());
        assertEquals("SOURCE", assertThrows(IllegalStateException.class, () -> importer.read(folder, outside)).getMessage());
    }
    @Test void bothRepositoriesRequirePreviewAndCreateVerifiedSourceAndDestinationBackups() throws Exception {
        for (boolean sql : List.of(false, true)) {
            Path folder = folder(); source(folder, "hub.yml", yaml("hub")); WarpRepository repo = repository(folder, sql); Warp original = original();
            var service = service(folder, repo, WarpConfig.createDefault(), original); UUID actor = UUID.randomUUID();
            try {
                var deleted = new DeletedWarp(UUID.randomUUID(), original, System.currentTimeMillis(), actor); repo.saveDeleted(List.of(deleted)).join();
                var preview = service.previewEssentialsImport(actor, "replace", worlds()).join(); assertEquals(original, repo.loadAll().join().get("hub"));
                var result = service.confirmEssentialsImport(actor, preview.token(), worlds()).join(); assertEquals(1, result.count());
                assertEquals(-5.5, repo.loadAll().join().get("hub").x()); assertEquals(repo.loadAll().join().get("hub"), service.getWarp("hub").orElseThrow());
                Path backup = Path.of(result.backup()); assertEquals(yaml("hub"), Files.readString(backup.resolve("essentials/hub.yml"))); assertTrue(Files.isRegularFile(backup.resolve("manifest.txt")));
                WarpRepository snapshot = repository(backup, false);
                try { snapshot.initialize().join(); assertEquals(original, snapshot.loadAll().join().get("hub")); assertTrue(snapshot.loadDeleted().join().contains(deleted)); } finally { snapshot.close().join(); }
                assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, preview.token(), worlds()).join());
            } finally { service.close().join(); }
        }
    }
    @Test void sourceAndDestinationChangesInvalidateConfirmationWithoutWriting() throws Exception {
        Path folder = folder(); source(folder, "hub.yml", yaml("hub")); WarpRepository repo = repository(folder, false); var service = service(folder, repo, WarpConfig.createDefault()); UUID actor = UUID.randomUUID();
        try {
            var preview = service.previewEssentialsImport(actor, "skip", worlds()).join(); source(folder, "hub.yml", yaml("hub").replace("y: 64.0", "y: 65.0"));
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, preview.token(), worlds()).join()); assertTrue(repo.loadAll().join().isEmpty());
            UUID token = service.previewEssentialsImport(actor, "skip", worlds()).join().token(); repo.save(original()).join();
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, token, worlds()).join()); assertEquals(1, repo.loadAll().join().size()); assertFalse(Files.exists(folder.resolve("warp-import-backups")));
        } finally { service.close().join(); }
    }
    @Test void previewsAreBoundToActorWorldsReloadAndQuit() throws Exception {
        Path folder = folder(); source(folder, "hub.yml", yaml("hub")); WarpRepository repo = repository(folder, false); var service = service(folder, repo, WarpConfig.createDefault()); UUID actor = UUID.randomUUID();
        try {
            var preview = service.previewEssentialsImport(actor, "skip", worlds()).join();
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(UUID.randomUUID(), preview.token(), worlds()).join());
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, preview.token(), Map.of()).join());
            service.cancelWarmupOnQuit(UUID.randomUUID()); // Other players quitting must not invalidate the staff preview.
            service.cancelWarmupOnQuit(UUID.fromString(actor.toString()));
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, preview.token(), worlds()).join());
            UUID reloaded = service.previewEssentialsImport(actor, "skip", worlds()).join().token(); service.updateConfig(WarpConfig.createDefault());
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, reloaded, worlds()).join()); assertTrue(repo.loadAll().join().isEmpty());
        } finally { service.close().join(); }
    }
    @Test void backupLimitPreventsAnyDestinationWrite() throws Exception {
        Path folder = folder(); source(folder, "hub.yml", yaml("hub")); Files.createDirectories(folder.resolve("warp-import-backups/old"));
        var d = WarpImportConfig.createDefault(); var config = settings(new WarpImportConfig(d.sourceFolder(), d.maxFiles(), d.maxFileBytes(), d.maxTotalBytes(), d.previewSeconds(), d.previewLines(), 1, d.category(), true, d.messages()));
        WarpRepository repo = repository(folder, false); var service = service(folder, repo, config); UUID actor = UUID.randomUUID();
        try {
            var preview = service.previewEssentialsImport(actor, "skip", worlds()).join();
            var failure = assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, preview.token(), worlds()).join());
            assertEquals("BACKUP_LIMIT", failure.getCause().getMessage()); assertTrue(repo.loadAll().join().isEmpty()); assertTrue(service.getAllWarps().isEmpty());
        } finally { service.close().join(); }
    }
    @Test void expiredPreviewCannotWriteAndOtherPlayersQuitDoesNotInvalidateOwner() throws Exception {
        Path folder = folder(); source(folder, "hub.yml", yaml("hub"));
        var d = WarpImportConfig.createDefault(); var config = settings(new WarpImportConfig(d.sourceFolder(), d.maxFiles(), d.maxFileBytes(), d.maxTotalBytes(), 1, d.previewLines(), d.maxBackups(), d.category(), true, d.messages()));
        WarpRepository repo = repository(folder, false); var service = service(folder, repo, config); UUID actor = UUID.randomUUID();
        try {
            var preview = service.previewEssentialsImport(actor, "skip", worlds()).join();
            Thread.sleep(1100); // Test thread only; never runs in a server execution context.
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, preview.token(), worlds()).join()); assertTrue(repo.loadAll().join().isEmpty());
            UUID fresh = service.previewEssentialsImport(actor, "skip", worlds()).join().token(); service.cancelWarmupOnQuit(UUID.randomUUID());
            assertEquals(1, service.confirmEssentialsImport(actor, fresh, worlds()).join().count());
        } finally { service.close().join(); }
    }
    @Test void warpCapacityRejectsWholePlanInsteadOfPartialImport() throws Exception {
        Path folder = folder(); source(folder, "hub.yml", yaml("hub")); Map<String, Warp> destination = new HashMap<>();
        for (int i = 0; i < 100; i++) destination.put("existing" + i, new Warp("existing" + i, worldId, "world", 0, 64, 0, 0, 0, null, "general", null, false, 0));
        assertEquals("LIMIT", assertThrows(IllegalStateException.class, () -> plan(folder, destination, "skip")).getMessage());
    }
    @Test void sqliteBatchFailureRollsBackAndRetainsBackupWithoutPublishingCache() throws Exception {
        Path folder = folder(); source(folder, "1.yml", yaml("first")); source(folder, "2.yml", yaml("second")); WarpRepository repo = repository(folder, true); var service = service(folder, repo, WarpConfig.createDefault()); UUID actor = UUID.randomUUID();
        try {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("tpcore.db")); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_second BEFORE INSERT ON tpcore_warps WHEN NEW.warp_name = 'second' BEGIN SELECT RAISE(ABORT, 'test rejection'); END");
            }
            var preview = service.previewEssentialsImport(actor, "skip", worlds()).join();
            assertThrows(Exception.class, () -> service.confirmEssentialsImport(actor, preview.token(), worlds()).join());
            assertTrue(repo.loadAll().join().isEmpty()); assertTrue(service.getAllWarps().isEmpty());
            assertTrue(Files.isRegularFile(folder.resolve("warp-import-backups").resolve(preview.token().toString()).resolve("manifest.txt")));
        } finally { service.close().join(); }
    }
}
