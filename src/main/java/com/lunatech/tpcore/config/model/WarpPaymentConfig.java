package com.lunatech.tpcore.config.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

@ConfigSerializable
public record WarpPaymentConfig(int timeoutSeconds, int decimalPlaces, String currency, String format,
    int resolutionHistoryLimit, Map<String, String> messages) {
    public WarpPaymentConfig {
        timeoutSeconds = Math.max(1, Math.min(60, timeoutSeconds));
        if (decimalPlaces < 0 || decimalPlaces > 8) throw new IllegalArgumentException("Payment decimal-places must be 0..8");
        if (currency == null) currency = "default";
        if (currency.isBlank() || currency.length() > 64) throw new IllegalArgumentException("Invalid payment currency label");
        if (format == null) format = "$<amount>";
        if (!format.contains("<amount>") || format.length() > 256) throw new IllegalArgumentException("Payment format must contain <amount>");
        resolutionHistoryLimit = Math.max(1, Math.min(1000, resolutionHistoryLimit));
        Map<String, String> defaults = new HashMap<>(Map.of(
            "creation-quote", "<prefix><yellow>Creating/overwriting warp <warp> costs <cost>.</yellow>",
            "acknowledged", "<prefix><green>Payment <ticket> acknowledged with an audit record and backup. No money was transferred.</green>",
            "resumed", "<prefix><green>Payment circuit reset. No money was transferred.</green>",
            "failed", "<prefix><red>Payment acknowledgement refused: <reason>. Check the evidence and server log.</red>",
            "reason-invalid_input", "Provide a ticket and an evidence note containing 1 to 256 characters",
            "reason-not_found", "That payment ticket does not exist",
            "reason-live_transfer", "A bank transfer or active warp still owns this payment; wait for it to finish",
            "reason-unresolved", "Live operations or unresolved payment evidence still exist",
            "reason-storage", "Payment evidence could not be safely updated; try later and check the server log",
            "usage", "<prefix><yellow>/warpstatus payments acknowledge <ticket> <evidence-note>. Independently verify the provider history first. This never transfers money.</yellow>"));
        if (messages != null) defaults.putAll(messages);
        messages = Map.copyOf(defaults);
    }

    public BigDecimal amount(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid payment amount");
        BigDecimal result = BigDecimal.valueOf(value).stripTrailingZeros();
        if (result.scale() > decimalPlaces) throw new IllegalArgumentException("Payment price exceeds configured decimal-places");
        return result.setScale(decimalPlaces);
    }
    public String display(BigDecimal amount) { return format.replace("<amount>", (amount.scale() < decimalPlaces ? amount.setScale(decimalPlaces) : amount).toPlainString()); }
    public static WarpPaymentConfig createDefault() { return new WarpPaymentConfig(10, 2, "default", "$<amount>", 100, null); }
}
