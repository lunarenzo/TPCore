package com.lunatech.tpcore.config.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record HomeEconomyConfig(boolean enabled, String teleportCost, String createCost,
    int maxUnresolvedPayments, int paymentHistoryLimit, Set<String> legacyAsyncProviders, Map<String, String> messages) {

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
        Map.entry("fee", "<gray>Home fee: <gold><amount></gold>.</gray>"),
        Map.entry("menu-fee", "Fee: <amount>"),
        Map.entry("economy-unavailable", "<red>Home payments are unavailable.</red>"),
        Map.entry("insufficient-funds", "<red>You do not have enough funds for this home action.</red>"),
        Map.entry("payment-rejected", "<red>The economy provider rejected your home payment.</red>"),
        Map.entry("payment-pending", "<yellow>The payment outcome is pending. Contact staff if it does not resolve.</yellow>"),
        Map.entry("payment-refunded", "<yellow>Your home fee of <gold><amount></gold> was refunded.</yellow>"),
        Map.entry("payment-not-found", "<yellow>No matching home payment was found.</yellow>"),
        Map.entry("payment-acknowledged", "<green>Payment <id> marked as reconciled.</green>"),
        Map.entry("payment-ack-denied", "<yellow>Only an unresolved payment without active work can be acknowledged.</yellow>"),
        Map.entry("payment-circuit", "<gray>Home payment circuit: <status></gray>"),
        Map.entry("payment-circuit-changed", "<yellow>Home payment circuit action accepted: <accepted>. Inspect /homepayments circuit.</yellow>"),
        Map.entry("payment-evidence", "<gray>Evidence: <evidence></gray>"),
        Map.entry("evidence-debit-pending", "Withdrawal intent persisted. A withdrawal may have started. No final debit receipt is recorded."),
        Map.entry("evidence-debit-unknown", "Withdrawal failed or its outcome could not be established. Check provider debit history."),
        Map.entry("evidence-teleport-pending", "Debit recorded. Home travel was prepared or dispatched. Await the arrival result."),
        Map.entry("evidence-teleport-unknown", "Debit recorded, but arrival outcome is unknown. Check the player's travel before refunding."),
        Map.entry("evidence-refund-pending", "Refund intent recorded. A refund may have started. Check provider credit history."),
        Map.entry("evidence-refund-unknown", "Refund outcome is unknown or incomplete. The recorded refund is the observed credit, if available."),
        Map.entry("evidence-creation-unknown", "Debit recorded, but creation outcome is uncertain. Check the saved home and provider history."),
        Map.entry("evidence-review", "Legacy REVIEW entry. Its interrupted operation is not recorded. Check debit, credit and home history."),
        Map.entry("evidence-debited", "Debit recorded. Creation has not yet been confirmed."),
        Map.entry("evidence-rejected", "Payment was rejected before a confirmed debit."),
        Map.entry("evidence-kept", "Home action completed and the recorded debit was kept."),
        Map.entry("evidence-refunded", "The provider confirmed the exact refund."),
        Map.entry("evidence-acknowledged", "Staff independently reconciled and acknowledged this entry."),
        Map.entry("payment-row", "<gray><id> | <player> | <action> <home> | <state> | <amount></gray>"),
        Map.entry("payment-detail", "<gray>Provider: <provider> | Debit: <debit> | Refund: <refund> | Updated: <updated> | Reconciled by: <operator></gray>"));

    public HomeEconomyConfig {
        teleportCost = validate(teleportCost == null ? "0.00" : teleportCost);
        createCost = validate(createCost == null ? "0.00" : createCost);
        if (maxUnresolvedPayments < 1 || maxUnresolvedPayments > 4096 || paymentHistoryLimit < 1 || paymentHistoryLimit > 4096)
            throw new IllegalArgumentException("Home payment limits must be between 1 and 4096");
        messages = messages == null ? Map.of() : Map.copyOf(messages);
        legacyAsyncProviders = legacyAsyncProviders == null ? Set.of() : Set.copyOf(legacyAsyncProviders);
        for (String provider : legacyAsyncProviders) {
            if (!provider.matches("[^/\\s]+/[^/\\s]+"))
                throw new IllegalArgumentException("Home legacy economy providers must use PluginName/provider.class.Name");
        }
    }

    private static String validate(String amount) {
        if (!amount.matches("[0-9]{1,12}(\\.[0-9]{1,8})?"))
            throw new IllegalArgumentException("Home costs must be nonnegative decimal amounts with up to 12 whole and 8 fractional digits");
        return amount;
    }

    public BigDecimal cost(boolean creation) {
        return enabled ? new BigDecimal(creation ? createCost : teleportCost) : BigDecimal.ZERO;
    }

    public String message(String key) { return messages.getOrDefault(key, DEFAULT_MESSAGES.get(key)); }

    public static HomeEconomyConfig createDefault() {
        return new HomeEconomyConfig(false, "0.00", "0.00", 256, 512, Set.of(), Map.of());
    }
}
