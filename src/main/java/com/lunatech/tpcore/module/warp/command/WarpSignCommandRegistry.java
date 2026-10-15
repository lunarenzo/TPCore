package com.lunatech.tpcore.module.warp.command;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.warp.service.WarpSignService;
import com.lunatech.tpcore.module.warp.service.WarpSignService.Mode;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;

public final class WarpSignCommandRegistry {
    private final WarpSignService signs;
    public WarpSignCommandRegistry(WarpSignService signs) { this.signs = signs; }

    public void register(Commands commands) {
        commands.register(Commands.literal("warpsign")
            .requires(source -> signs.canManage(source.getSender()))
            .executes(context -> { signs.help(context.getSource().getSender()); return Command.SINGLE_SUCCESS; })
            .then(Commands.literal("create").requires(source -> signs.allowed(source.getSender(), Permissions.WARP_SIGN_CREATE))
                .then(Commands.argument("name", StringArgumentType.word()).executes(context -> {
                    signs.prepare(context.getSource().getSender(), Mode.CREATE, StringArgumentType.getString(context, "name")); return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("remove").requires(source -> signs.allowed(source.getSender(), Permissions.WARP_SIGN_REMOVE))
                .executes(context -> { signs.prepare(context.getSource().getSender(), Mode.REMOVE, null); return Command.SINGLE_SUCCESS; }))
            .then(Commands.literal("info").requires(source -> signs.allowed(source.getSender(), Permissions.WARP_SIGN_INFO))
                .executes(context -> { signs.prepare(context.getSource().getSender(), Mode.INFO, null); return Command.SINGLE_SUCCESS; }))
            .then(Commands.literal("cancel").executes(context -> { signs.cancel(context.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
            .build());
    }
}
