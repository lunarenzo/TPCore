package com.lunatech.tpcore.module.rtp.command;

import com.lunatech.tpcore.constant.Permissions;
import com.lunatech.tpcore.module.rtp.config.RtpConfig;
import com.lunatech.tpcore.module.rtp.economy.RtpEconomy;
import com.lunatech.tpcore.module.rtp.repository.impl.SqliteRtpPaymentRepository;
import com.lunatech.tpcore.module.rtp.model.RtpPayment;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class RtpPaymentCommands {
    private final JavaPlugin plugin;
    private final Supplier<RtpConfig> config;
    private final Supplier<SqliteRtpPaymentRepository> repository;
    private final Supplier<RtpEconomy> economy;
    private final AtomicInteger pending = new AtomicInteger();
    private final MiniMessage mini = MiniMessage.miniMessage();
    RtpPaymentCommands(JavaPlugin plugin, Supplier<RtpConfig> config, Supplier<SqliteRtpPaymentRepository> repository, Supplier<RtpEconomy> economy) {
        this.plugin = plugin; this.config = config; this.repository = repository; this.economy = economy;
    }
    void register(Commands commands) {
        commands.register(Commands.literal("rtppayments").requires(source -> source.getSender().hasPermission(Permissions.RTP_ADMIN_PAYMENTS))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource().getSender(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> list(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "page")))))
            .then(Commands.literal("show").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> entry(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "id"), false))))
            .then(Commands.literal("acknowledge").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> entry(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "id"), true))))
            .then(Commands.literal("circuit").executes(ctx -> circuit(ctx.getSource().getSender(), "status"))
                .then(Commands.literal("isolate").executes(ctx -> circuit(ctx.getSource().getSender(), "isolate")))
                .then(Commands.literal("reset").executes(ctx -> circuit(ctx.getSource().getSender(), "reset"))))
            .build(), "Inspect and acknowledge independently reconciled RTP payments", List.of());
    }
    private int list(CommandSender sender, int page) {
        var journal = repository.get();
        if (journal == null) { send(sender, "economy-unavailable"); return Command.SINGLE_SUCCESS; }
        reply(sender, () -> journal.listPayments((page - 1L) * 10), entries -> target -> {
            if (entries.isEmpty()) send(target, "payment-not-found");
            else entries.forEach(payment -> row(target, payment));
        });
        return Command.SINGLE_SUCCESS;
    }
    private int entry(CommandSender sender, String raw, boolean acknowledge) {
        UUID id;
        try { id = UUID.fromString(raw); } catch (IllegalArgumentException invalid) { send(sender, "payment-not-found"); return Command.SINGLE_SUCCESS; }
        var journal = repository.get(); var bank = economy.get();
        if (journal == null || bank == null) { send(sender, "economy-unavailable"); return Command.SINGLE_SUCCESS; }
        String operator = sender.getName();
        if (acknowledge) reply(sender, () -> bank.acknowledge(id, operator, config.get().economy().paymentHistoryLimit()),
            accepted -> target -> send(target, accepted ? "payment-acknowledged" : "payment-ack-denied", Placeholder.unparsed("id", id.toString())));
        else reply(sender, () -> journal.findPayment(id), found -> target -> {
            if (found.isEmpty()) { send(target, "payment-not-found"); return; }
            var payment = found.get(); row(target, payment);
            send(target, "payment-detail", Placeholder.unparsed("provider", payment.provider()),
                Placeholder.unparsed("debit", payment.debit() == null ? "—" : payment.debit().toPlainString()),
                Placeholder.unparsed("refund", payment.refund() == null ? "—" : payment.refund().toPlainString()),
                Placeholder.unparsed("updated", Instant.ofEpochMilli(payment.updated()).toString()),
                Placeholder.unparsed("operator", payment.operator() == null ? "—" : payment.operator()));
            send(target, "payment-evidence", Placeholder.unparsed("evidence", config.get().economy().message("evidence-" + payment.state().name().toLowerCase(Locale.ROOT).replace('_', '-'))));
        });
        return Command.SINGLE_SUCCESS;
    }
    private void row(CommandSender target, RtpPayment payment) {
        send(target, "payment-row", Placeholder.unparsed("id", payment.id().toString()),
            Placeholder.unparsed("player", payment.player().toString()), Placeholder.unparsed("world", payment.world()),
            Placeholder.unparsed("action", payment.action()), Placeholder.unparsed("state", payment.state().name()),
            Placeholder.unparsed("amount", payment.requested().toPlainString()));
    }
    private int circuit(CommandSender sender, String action) {
        var bank = economy.get();
        if (bank == null) { send(sender, "economy-unavailable"); return Command.SINGLE_SUCCESS; }
        if (!action.equals("status")) send(sender, "payment-circuit-changed", Placeholder.unparsed("accepted", Boolean.toString(
            action.equals("isolate") ? bank.isolateProvider() : bank.resetCircuit())));
        send(sender, "payment-circuit", Placeholder.unparsed("status", bank.circuitStatus()));
        return Command.SINGLE_SUCCESS;
    }
    private <T> void reply(CommandSender sender, Supplier<CompletableFuture<T>> query, Function<T, Consumer<CommandSender>> render) {
        if (pending.incrementAndGet() > 32) { pending.decrementAndGet(); send(sender, "economy-unavailable"); return; }
        var handle = new WeakReference<>(sender);
        CompletableFuture<T> result;
        try { result = query.get(); } catch (RuntimeException failure) { pending.decrementAndGet(); send(sender, "economy-unavailable"); return; }
        result.whenComplete((value, failure) -> {
            pending.decrementAndGet();
            var target = handle.get(); if (target == null) return;
            Runnable deliver = () -> {
                var online = handle.get();
                if (online == null || !online.hasPermission(Permissions.RTP_ADMIN_PAYMENTS)) return;
                if (failure != null) send(online, "economy-unavailable"); else render.apply(value).accept(online);
            };
            try {
                if (target instanceof Player player) player.getScheduler().run(plugin, ignored -> deliver.run(), null);
                else Bukkit.getGlobalRegionScheduler().execute(plugin, deliver);
            } catch (RuntimeException retired) { plugin.getSLF4JLogger().debug("RTP payment response target retired", retired); }
        });
    }
    private void send(CommandSender sender, String key, TagResolver... tags) {
        sender.sendMessage(mini.deserialize(config.get().messages().prefix() + config.get().economy().message(key), tags));
    }
}
