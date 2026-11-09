package com.lunatech.tpcore.config.model;

import java.math.BigDecimal;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record HomeEconomyConfig(boolean enabled, String teleportCost, String createCost,
    int maxUnresolvedPayments, int paymentHistoryLimit, Map<String, String> messages) {

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
        Map.entry("payment-row", "<gray><id> | <player> | <action> <home> | <state> | <amount></gray>"),
        Map.entry("payment-detail", "<gray>Provider: <provider> | Debit: <debit> | Refund: <refund> | Updated: <updated> | Reconciled by: <operator></gray>"));

    public HomeEconomyConfig {
        teleportCost = validate(teleportCost == null ? "0.00" : teleportCost);
        createCost = validate(createCost == null ? "0.00" : createCost);
        if (maxUnresolvedPayments < 1 || maxUnresolvedPayments > 4096 || paymentHistoryLimit < 1 || paymentHistoryLimit > 4096)
            throw new IllegalArgumentException("Home payment limits must be between 1 and 4096");
        messages = messages == null ? Map.of() : Map.copyOf(messages);
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
        return new HomeEconomyConfig(false, "0.00", "0.00", 256, 512, Map.of());
    }
}
