package com.lunatech.tpcore.command;

import com.lunatech.tpcore.config.ModularConfigManager;
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

public final class TPCoreAdminCommandRegistry {

    private final JavaPlugin plugin;
    private final ModularConfigManager configManager;
    private final MiniMessage miniMessage;

    public TPCoreAdminCommandRegistry(JavaPlugin plugin, ModularConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
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
                    .build(),
                "TPCore Administrative Root Command",
                List.of("tpc")
            );
        });
    }

    private void executeReload(CommandSender sender, String targetModule) {
        String prefix = "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ";

        if (targetModule.equalsIgnoreCase("all")) {
            Map<String, Boolean> results = this.configManager.reloadAllModules();
            if (results.isEmpty()) {
                sender.sendMessage(this.miniMessage.deserialize(prefix + "<yellow>No modules currently registered to reload.</yellow>"));
                return;
            }

            int successCount = 0;
            for (Map.Entry<String, Boolean> entry : results.entrySet()) {
                String mod = entry.getKey();
                boolean ok = entry.getValue();
                if (ok) {
                    successCount++;
                    sender.sendMessage(this.miniMessage.deserialize(prefix + "<gray>Module <yellow>" + mod + "</yellow>: <green>Reloaded successfully.</green></gray>"));
                } else {
                    sender.sendMessage(this.miniMessage.deserialize(prefix + "<gray>Module <yellow>" + mod + "</yellow>: <red>Failed to reload! Check server console for errors.</red></gray>"));
                }
            }
            sender.sendMessage(this.miniMessage.deserialize(prefix + "<green>Reload complete. (<gold>" + successCount + "/" + results.size() + "</gold> modules reloaded)</green>"));
        } else {
            String modName = targetModule.toLowerCase();
            Set<String> modules = this.configManager.getRegisteredModuleNames();
            if (!modules.contains(modName)) {
                sender.sendMessage(this.miniMessage.deserialize(prefix + "<red>Unknown module: <yellow>" + targetModule + "</yellow>. Registered: <gold>" + String.join(", ", modules) + "</gold></red>"));
                return;
            }

            boolean ok = this.configManager.reloadModule(modName);
            if (ok) {
                sender.sendMessage(this.miniMessage.deserialize(prefix + "<green>Module <yellow>" + modName + "</yellow> reloaded successfully.</green>"));
            } else {
                sender.sendMessage(this.miniMessage.deserialize(prefix + "<red>Failed to reload module <yellow>" + modName + "</yellow>! Check server console for errors.</red>"));
            }
        }
    }

    private void sendVersionMessage(CommandSender sender) {
        String prefix = "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ";
        String version = this.plugin.getPluginMeta().getVersion();
        String mcVersion = String.format("%d.%d.%d", ServerVersion.MAJOR, ServerVersion.MINOR, ServerVersion.PATCH);
        Set<String> modules = this.configManager.getRegisteredModuleNames();

        sender.sendMessage(this.miniMessage.deserialize(prefix + "<green>TPCore Version: <gold>v" + version + "</gold></green>"));
        sender.sendMessage(this.miniMessage.deserialize(prefix + "<gray>Server Engine: <white>Minecraft " + mcVersion + "</white></gray>"));
        sender.sendMessage(this.miniMessage.deserialize(prefix + "<gray>Active Modules: <gold>" + String.join(", ", modules) + "</gold></gray>"));
    }

    private void sendHelpMessage(CommandSender sender) {
        String prefix = "<gradient:#00D2FF:#3A7BD5><bold>TPCore</bold></gradient> <dark_gray>»</dark_gray> ";
        sender.sendMessage(this.miniMessage.deserialize(prefix + "<gray>Admin Commands:</gray>"));
        sender.sendMessage(this.miniMessage.deserialize(prefix + " <gold>/tpcore reload [all|module]</gold> <dark_gray>-</dark_gray> <gray>Reload configuration files</gray>"));
        sender.sendMessage(this.miniMessage.deserialize(prefix + " <gold>/tpcore version</gold> <dark_gray>-</dark_gray> <gray>Display plugin version & active modules</gray>"));
    }
}
