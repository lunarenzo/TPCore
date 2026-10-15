package com.lunatech.tpcore.config.model;

import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record WarpDiagnosticMessages(List<String> status, List<String> paymentHeader, String paymentEntry,
    String noPayments, String failure, String yes, String no, String unknown) {

    public WarpDiagnosticMessages {
        if (status == null || paymentHeader == null || paymentEntry == null || noPayments == null
            || failure == null || yes == null || no == null || unknown == null) {
            WarpDiagnosticMessages defaults = createDefault();
            if (status == null) status = defaults.status();
            if (paymentHeader == null) paymentHeader = defaults.paymentHeader();
            if (paymentEntry == null) paymentEntry = defaults.paymentEntry();
            if (noPayments == null) noPayments = defaults.noPayments();
            if (failure == null) failure = defaults.failure();
            if (yes == null) yes = defaults.yes();
            if (no == null) no = defaults.no();
            if (unknown == null) unknown = defaults.unknown();
        }
        status = List.copyOf(status);
        paymentHeader = List.copyOf(paymentHeader);
    }

    public static WarpDiagnosticMessages createDefault() {
        return new WarpDiagnosticMessages(List.of(
            "<prefix><gold>Warp status</gold> <gray>(live observations)</gray>",
            "<gray>Storage: <white><storage></white> | Ready: <white><ready></white> | Module enabled: <white><enabled></white></gray>",
            "<gray>Warps: <white><warps></white> | Configured limit (0 = unlimited): <white><warp-limit></white> | Cache capacity: <white><cache-capacity></white></gray>",
            "<gray>Operations: <white><pending>/<operation-capacity></white> | Teleports: <white><teleports></white> | Mutations: <white><mutations></white> | Migration: <white><migrating></white></gray>",
            "<gray>Economy usable: <white><economy></white> | Journal ready: <white><journal-ready></white> | Reconciliation required: <white><reconciliation></white></gray>",
            "<gray>Payment operations: <white><payment-operations></white> | Retained intents: <white><intents>/<intent-capacity></white> (may include active payments)</gray>",
            "<gray>Authorized staff: /warpstatus payments [page] shows evidence only. No transfers are retried.</gray>"),
            List.of("<prefix><gold>Payment evidence</gold> <gray>Page <page>/<pages> | <total> retained intents</gray>",
                "<yellow>warps/refunds.properties records player UUID | amount. These records do not prove a debit or refund completed. Compare provider debit/credit history before changing anything; never retry an ambiguous refund blindly.</yellow>"),
            "<gray>Ticket <white><ticket></white> | Record: <white><record></white></gray>",
            "<prefix><gray>No retained payment intents.</gray>",
            "<prefix><red>Payment evidence is unavailable. Check the server log and preserve warps/refunds.properties.</red>",
            "yes", "no", "unavailable");
    }
}
