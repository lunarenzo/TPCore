package com.lunatech.tpcore.command;

import com.lunatech.tpcore.config.ModularConfigManager;
import com.lunatech.tpcore.config.ReloadableModule;
import com.lunatech.tpcore.config.model.CoreConfig;
import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.platform.ServerVersion;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class TPCoreAdminCommandRegistry {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private final Supplier<CoreConfig> configSupplier;
    private final MiniMessage miniMessage;

    public TPCoreAdminCommandRegistry(JavaPlugin plugin, ModularConfigManager configManager, Supplier<CoreConfig> configSupplier) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.configSupplier = configSupplier;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void registerAll() {
        this.plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                Commands.literal("tpcore")
                    .requires(src -> src.getSender().hasPermission(Permissions.ADMIN_RELOAD))
                    .executes(ctx -> {
                        this.sendHelpMessage(ctx.getSource().getSender());
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.literal("version")
                        .executes(ctx -> {
                            this.sendVersionMessage(ctx.getSource().getSender());
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                    )
                    .then(Commands.literal("reload")
                        .executes(ctx -> {
                            this.executeReload(ctx.getSource().getSender(), "all");
                            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("target", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                builder.suggest("all");
                                builder.suggest("core");
                                for (String name : this.configManager.getRegisteredModuleNames()) {
                                    builder.suggest(name);
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> {
                                String target = StringArgumentType.getString(ctx, "target");
                                this.executeReload(ctx.getSource().getSender(), target);
                                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                            })
                        )
                    )
                    .then(Commands.literal("migrate")
                        .requires(src -> src.getSender().hasPermission(Permissions.ADMIN_MIGRATE) || src.getSender().hasPermission(Permissions.ADMIN_RELOAD))
                        .then(Commands.argument("module", StringArgumentType.string())
                            .suggests((context, builder) -> {
                                String remaining = builder.getRemaining().toLowerCase();
                                for (String name : this.configManager.getRegisteredModuleNames()) {
                                    ReloadableModule mod = this.configManager.getModule(name);
                                    if (mod != null && mod.supportsMigration() && name.toLowerCase().startsWith(remaining)) {
                                        builder.suggest(name);
                                    }
                                }
                                return builder.buildFuture();
                            })
                            .then(Commands.argument("from", StringArgumentType.string())
                                .suggests((context, builder) -> {
                                    builder.suggest("sqlite");
                                    builder.suggest("yaml");
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("to", StringArgumentType.string())
                                    .suggests((context, builder) -> {
                                        builder.suggest("sqlite");
                                        builder.suggest("yaml");
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        String moduleName = StringArgumentType.getString(ctx, "module");
                                        String from = StringArgumentType.getString(ctx, "from");
                                        String to = StringArgumentType.getString(ctx, "to");
                                        this.executeMigration(ctx.getSource().getSender(), moduleName, from, to);
                                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )
                    .build(),
                "TPCore Administrative Root Command",
                List.of("tpc")
            );
        });
    }

    private void executeReload(CommandSender sender, String targetModule) {
        if (!this.configManager.tryLockReload()) {
            this.sendFormatted(sender, "<prefix><red>A configuration reload is already in progress! Please wait.</red>", Map.of());
            return;
        }

        this.plugin.getServer().getAsyncScheduler().runNow(this.plugin, scheduledTask -> {
            try {
                CoreConfig.CoreMessages msgs = this.configSupplier.get().messages();

                if (targetModule.equalsIgnoreCase("all")) {
                    Map<String, Boolean> results = this.configManager.reloadAllModules();
                    if (results.isEmpty()) {
                        this.sendFormatted(sender, msgs.reloadNoModules(), Map.of());
                        return;
                    }

                    int successCount = 0;
                    for (Map.Entry<String, Boolean> entry : results.entrySet()) {
                        String mod = entry.getKey();
                        boolean ok = entry.getValue();
                        if (ok) {
                            successCount++;
                            if (mod.equalsIgnoreCase("core")) {
                                this.sendFormatted(sender, msgs.reloadCoreSuccess(), Map.of());
                            } else {
                                this.sendFormatted(sender, msgs.reloadModuleSuccess(), Map.of("module", mod));
                            }
                        } else {
                            if (mod.equalsIgnoreCase("core")) {
                                this.sendFormatted(sender, msgs.reloadCoreFail(), Map.of());
                            } else {
                                this.sendFormatted(sender, msgs.reloadModuleFail(), Map.of("module", mod));
                            }
                        }
                    }
                    this.sendFormatted(sender, msgs.reloadAllComplete(), Map.of(
                        "success", String.valueOf(successCount),
                        "total", String.valueOf(results.size())
                    ));
                } else if (targetModule.equalsIgnoreCase("core")) {
                    boolean ok = this.configManager.reloadCoreConfig();
                    if (ok) {
                        this.sendFormatted(sender, msgs.reloadCoreSuccess(), Map.of());
                    } else {
                        this.sendFormatted(sender, msgs.reloadCoreFail(), Map.of());
                    }
                } else {
                    String modName = targetModule.toLowerCase();
                    Set<String> modules = this.configManager.getRegisteredModuleNames();
                    if (!modules.contains(modName)) {
                        this.sendFormatted(sender, msgs.reloadUnknownModule(), Map.of(
                            "module", targetModule,
                            "modules", String.join(", ", modules)
                        ));
                        return;
                    }

                    boolean ok = this.configManager.reloadModule(modName);
                    if (ok) {
                        this.sendFormatted(sender, msgs.reloadModuleSuccess(), Map.of("module", modName));
                    } else {
                        this.sendFormatted(sender, msgs.reloadModuleFail(), Map.of("module", modName));
                    }
                }
            } finally {
                this.configManager.unlockReload();
            }
        });
    }

    private void executeMigration(CommandSender sender, String moduleName, String fromStorage, String toStorage) {
        CoreConfig.CoreMessages msgs = this.configSupplier.get().messages();
        ReloadableModule module = this.configManager.getModule(moduleName);

        if (module == null) {
            this.sendFormatted(sender, msgs.reloadUnknownModule(), Map.of(
                "module", moduleName,
                "modules", String.join(", ", this.configManager.getRegisteredModuleNames())
            ));
            return;
        }

        if (!module.supportsMigration()) {
            this.sendFormatted(sender, msgs.migrateNotSupported(), Map.of("module", moduleName));
            return;
        }

        if (fromStorage.equalsIgnoreCase(toStorage)) {
            this.sendFormatted(sender, msgs.migrateSameEngine(), Map.of());
            return;
        }

        this.sendFormatted(sender, "<prefix><gray>Starting data migration for module <yellow>" + moduleName + "</yellow> (<yellow>" + fromStorage + "</yellow> -> <yellow>" + toStorage + "</yellow>)...</gray>", Map.of());

        module.migrateData(fromStorage, toStorage).whenComplete((count, throwable) -> {
            if (throwable != null) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                if ("INVALID_STORAGE_TYPE".equals(cause.getMessage())) {
                    String invalid = (!fromStorage.equalsIgnoreCase("SQLITE") && !fromStorage.equalsIgnoreCase("YAML")) ? fromStorage : toStorage;
                    this.sendFormatted(sender, msgs.migrateInvalidEngine(), Map.of("engine", invalid));
                } else if ("SAME_STORAGE_TYPE".equals(cause.getMessage())) {
                    this.sendFormatted(sender, msgs.migrateSameEngine(), Map.of());
                } else {
                    this.plugin.getSLF4JLogger().error("Data migration failure for module {}", moduleName, cause);
                    this.sendFormatted(sender, msgs.migrateFail(), Map.of(
                        "module", moduleName,
                        "from", fromStorage,
                        "to", toStorage
                    ));
                }
                return;
            }

            this.sendFormatted(sender, msgs.migrateSuccess(), Map.of(
                "count", String.valueOf(count),
                "module", moduleName,
                "from", fromStorage,
                "to", toStorage
            ));
        });
    }

    private void sendVersionMessage(CommandSender sender) {
        CoreConfig.CoreMessages msgs = this.configSupplier.get().messages();
        String version = this.plugin.getPluginMeta().getVersion();
        String mcVersion = String.format("%d.%d.%d", ServerVersion.MAJOR, ServerVersion.MINOR, ServerVersion.PATCH);
        Set<String> modules = this.configManager.getRegisteredModuleNames();

        this.sendFormatted(sender, msgs.versionInfo(), Map.of(
            "version", version,
            "mcversion", mcVersion,
            "modules", String.join(", ", modules)
        ));
    }

    private void sendHelpMessage(CommandSender sender) {
        CoreConfig.CoreMessages msgs = this.configSupplier.get().messages();
        this.sendFormatted(sender, msgs.adminHelp(), Map.of());
    }

    private void sendFormatted(CommandSender sender, String template, Map<String, String> placeholders) {
        CoreConfig config = this.configSupplier.get();
        String prefix = config.prefix();
        String formatted = template.replace("<prefix>", prefix);

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            formatted = formatted.replace("<" + entry.getKey() + ">", entry.getValue());
        }

        sender.sendMessage(this.miniMessage.deserialize(formatted));
    }
}
