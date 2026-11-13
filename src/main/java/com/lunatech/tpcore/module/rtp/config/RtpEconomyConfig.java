package com.lunatech.tpcore.module.rtp.config;

import java.util.Map;
import java.util.Set;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record RtpEconomyConfig(boolean enabled,
    int maxUnresolvedPayments, int paymentHistoryLimit, int settlementDrainSeconds, Set<String> legacyAsyncProviders, Map<String, String> messages) {

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
        Map.entry("fee", "<gray>RTP fee: <gold><amount></gold>.</gray>"),
        Map.entry("economy-unavailable", "<red>RTP payments are unavailable.</red>"),
        Map.entry("insufficient-funds", "<red>You do not have enough funds for this RTP action.</red>"),
        Map.entry("payment-rejected", "<red>The economy provider rejected your RTP payment.</red>"),
        Map.entry("payment-pending", "<yellow>The payment outcome is pending. Contact staff if it does not resolve.</yellow>"),
        Map.entry("payment-refunded", "<yellow>Your RTP fee of <gold><amount></gold> was refunded.</yellow>"),
        Map.entry("payment-not-found", "<yellow>No matching RTP payment was found.</yellow>"),
        Map.entry("payment-acknowledged", "<green>Payment <id> marked as reconciled.</green>"),
        Map.entry("payment-ack-denied", "<yellow>Only an unresolved payment without active work can be acknowledged.</yellow>"),
        Map.entry("payment-circuit", "<gray>RTP payment circuit: <status></gray>"),
        Map.entry("payment-circuit-changed", "<yellow>RTP payment circuit action accepted: <accepted>. Inspect /rtppayments circuit.</yellow>"),
        Map.entry("payment-evidence", "<gray>Evidence: <evidence></gray>"),
        Map.entry("evidence-debit-pending", "Withdrawal intent persisted. A withdrawal may have started. No final debit receipt is recorded."),
        Map.entry("evidence-debit-unknown", "Withdrawal failed or its outcome could not be established. Check provider debit history."),
        Map.entry("evidence-teleport-pending", "Debit recorded. RTP travel was prepared or dispatched. Await the arrival result."),
        Map.entry("evidence-teleport-unknown", "Debit recorded, but arrival outcome is unknown. Check the player's travel before refunding."),
        Map.entry("evidence-refund-pending", "Refund intent recorded. A refund may have started. Check provider credit history."),
        Map.entry("evidence-refund-unknown", "Refund outcome is unknown or incomplete. The recorded refund is the observed credit, if available."),
        Map.entry("evidence-rejected", "Payment was rejected before a confirmed debit."),
        Map.entry("evidence-kept", "RTP action completed and the recorded debit was kept."),
        Map.entry("evidence-refunded", "The provider confirmed the exact refund."),
        Map.entry("evidence-acknowledged", "Staff independently reconciled and acknowledged this entry."),
        Map.entry("payment-row", "<gray><id> | <player> | <action> <world> | <state> | <amount></gray>"),
        Map.entry("payment-detail", "<gray>Provider: <provider> | Debit: <debit> | Refund: <refund> | Updated: <updated> | Reconciled by: <operator></gray>"));

    public RtpEconomyConfig {
        if (maxUnresolvedPayments < 1 || maxUnresolvedPayments > 4096 || paymentHistoryLimit < 1 || paymentHistoryLimit > 4096)
            throw new IllegalArgumentException("RTP payment limits must be between 1 and 4096");
        if (settlementDrainSeconds < 1 || settlementDrainSeconds > 120) throw new IllegalArgumentException("RTP settlement drain must be 1..120 seconds");
        messages = messages == null ? Map.of() : Map.copyOf(messages);
        legacyAsyncProviders = legacyAsyncProviders == null ? Set.of() : Set.copyOf(legacyAsyncProviders);
        for (String provider : legacyAsyncProviders) {
            if (!provider.matches("[^/\\s]+/[^/\\s]+"))
                throw new IllegalArgumentException("RTP legacy economy providers must use PluginName/provider.class.Name");
        }
    }

    public String message(String key) { return messages.getOrDefault(key, DEFAULT_MESSAGES.get(key)); }

    public static RtpEconomyConfig createDefault() {
        return new RtpEconomyConfig(false, 256, 512, 10, Set.of(), Map.of());
    }
}
