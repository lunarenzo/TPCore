package com.lunatech.tpcore.module.home;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.config.model.HomeConfig;
import com.lunatech.tpcore.module.home.command.HomeCommandRegistry;
import com.lunatech.tpcore.module.home.engine.ConcurrentTeleportPipelineEngine;
import com.lunatech.tpcore.module.home.gui.HomeMenus;
import com.lunatech.tpcore.module.home.hook.HomeNameForm;
import com.lunatech.tpcore.module.home.hook.impl.FloodgateHomeNameForm;
import com.lunatech.tpcore.module.home.listener.HomeMenuListener;
import com.lunatech.tpcore.module.home.listener.HomeTravelListener;
import com.lunatech.tpcore.module.home.repository.impl.SqliteHomeRepository;
import com.lunatech.tpcore.module.home.service.impl.DefaultHomeService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class HomeModule implements ReloadableModule {
    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private final ConcurrentTeleportPipelineEngine engine;
    private final HomeMenus menus;
    private volatile HomeConfig config;
    private volatile DefaultHomeService service;
    private HomeTravelListener listener;
    private CompletableFuture<Void> stopped = CompletableFuture.completedFuture(null);
    private boolean active;
    private boolean commandsRegistered;

    public HomeModule(JavaPlugin plugin, ModularConfigManager configManager, HomeConfig config) {
        this.plugin = plugin; this.configManager = configManager; this.config = config;
        engine = new ConcurrentTeleportPipelineEngine(plugin, config.runtime().maxRequests());
        menus = new HomeMenus(plugin, () -> service, () -> this.config, bedrockForms());
        configManager.registerModule(this);
    }

    private HomeNameForm bedrockForms() {
        if (plugin.getServer().getPluginManager().isPluginEnabled("floodgate")) {
            try {
                return new FloodgateHomeNameForm();
            } catch (LinkageError unavailable) {
                plugin.getSLF4JLogger().warn("Home cannot load the Floodgate form adapter", unavailable);
            }
        }
        return HomeNameForm.UNAVAILABLE;
    }

    @Override public String getModuleName() { return "home"; }

    @Override public boolean reloadConfig() {
        var claimed = new AtomicBoolean();
        var applied = new CompletableFuture<Boolean>();
        try {
            HomeConfig next = configManager.tryLoadModuleConfig("home", HomeConfig.class, HomeConfig.createDefault(), false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(next.runtime().timeoutSeconds());
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                if (!claimed.compareAndSet(false, true)) return;
                if (System.nanoTime() - deadline >= 0 || !plugin.isEnabled()) { applied.complete(false); return; }
                try {
                    boolean restart = !next.enabled() || service == null || !service.ready()
                        || next.runtime().storageQueueCapacity() != config.runtime().storageQueueCapacity();
                    config = next;
                    menus.invalidate();
                    if (restart) {
                        stop();
                        startWhenStopped();
                    }
                    applied.complete(true); // Config accepted; storage readiness is separate.
                } catch (RuntimeException failure) { applied.completeExceptionally(failure); }
            });
            try { return applied.get(next.runtime().timeoutSeconds(), TimeUnit.SECONDS); }
            catch (TimeoutException timeout) { return !claimed.compareAndSet(false, true) && applied.join(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return !claimed.compareAndSet(false, true) && applied.join();
            }
        } catch (Exception failure) {
            plugin.getSLF4JLogger().error("Failed to reload Home configuration", failure);
            return false;
        }
    }

    public void enable() {
        active = true;
        if (!commandsRegistered) {
            new HomeCommandRegistry(plugin, () -> service, () -> config, menus).registerAll();
            plugin.getServer().getPluginManager().registerEvents(new HomeMenuListener(menus), plugin);
            commandsRegistered = true;
        }
        if (config.enabled() && service == null) start();
    }

    private void start() {
        var repository = new SqliteHomeRepository(plugin.getDataFolder(), plugin.getSLF4JLogger(), config.runtime().storageQueueCapacity());
        service = new DefaultHomeService(plugin, repository, () -> config, engine);
        listener = new HomeTravelListener(plugin, () -> service, () -> config);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private void stop() {
        var current = service;
        service = null;
        if (listener != null) { HandlerList.unregisterAll(listener); listener = null; }
        if (current == null) return;
        var closing = current.close();
        stopped = closing;
        closing.whenComplete((ignored, error) -> {
            if (error != null) plugin.getSLF4JLogger().error("Home storage shutdown failed", error);
            else if (plugin.isEnabled()) {
                try {
                    Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                        if (stopped == closing) startWhenStopped();
                    });
                } catch (RuntimeException failure) { plugin.getSLF4JLogger().warn("Cannot reschedule Home startup", failure); }
            }
        });
    }

    private void startWhenStopped() {
        if (active && config.enabled() && service == null && stopped.isDone() && !stopped.isCompletedExceptionally()) start();
    }

    public void disable() {
        active = false;
        menus.invalidate();
        stop();
    }
}
