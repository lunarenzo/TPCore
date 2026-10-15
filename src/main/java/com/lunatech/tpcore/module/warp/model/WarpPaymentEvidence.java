package com.lunatech.tpcore.module.warp.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.time.Instant;

/** Persisted observations, never an instruction to replay a transfer. */
public record WarpPaymentEvidence(UUID playerId, String operation, String warp, String provider, String currency,
    BigDecimal requested, BigDecimal debited, BigDecimal credited, String phase, long createdAt, long updatedAt, String detail) {
    public WarpPaymentEvidence {
        if (playerId == null || requested == null || requested.signum() < 0) throw new IllegalArgumentException("Invalid payment evidence");
        for (String text : new String[] {operation, warp, provider, currency, phase, detail})
            if (text == null || text.length() > 256) throw new IllegalArgumentException("Oversized payment evidence");
        for (BigDecimal value : new BigDecimal[] {requested, debited, credited})
            if (value != null && (value.signum() < 0 || value.precision() > 384 || Math.abs((long) value.scale()) > 384
                || value.toPlainString().length() > 384)) throw new IllegalArgumentException("Invalid receipt amount");
    }
    public WarpPaymentEvidence observed(String nextPhase, BigDecimal debit, BigDecimal credit, String description) {
        return new WarpPaymentEvidence(playerId, operation, warp, provider, currency, requested,
            debit == null ? debited : debit, credit == null ? credited : credit, nextPhase, createdAt, System.currentTimeMillis(),
            description == null ? "" : description.substring(0, Math.min(256, description.length())));
    }
    private static String text(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String plain(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static String number(BigDecimal value) { return value == null ? "?" : value.toPlainString(); }
    private static BigDecimal decimal(String value) { return value.equals("?") ? null : new BigDecimal(value); }
    public String encode() {
        return String.join("|", "v2", playerId.toString(), text(operation), text(warp), text(provider), text(currency),
            number(requested), number(debited), number(credited), text(phase), Long.toString(createdAt), Long.toString(updatedAt), text(detail));
    }
    public static WarpPaymentEvidence decode(String value) {
        String[] fields = value.split("\\|", -1);
        if (fields.length != 13 || !fields[0].equals("v2")) throw new IllegalArgumentException("Invalid payment evidence schema");
        return new WarpPaymentEvidence(UUID.fromString(fields[1]), plain(fields[2]), plain(fields[3]), plain(fields[4]), plain(fields[5]),
            decimal(fields[6]), decimal(fields[7]), decimal(fields[8]), plain(fields[9]), Long.parseLong(fields[10]), Long.parseLong(fields[11]), plain(fields[12]));
    }
    public String describe() {
        return "player=" + playerId + "; operation=" + operation + "; warp=" + warp + "; provider=" + provider + "; currency=" + currency
            + "; requested=" + number(requested) + "; debited=" + number(debited) + "; credited=" + number(credited)
            + "; phase=" + phase + "; created=" + Instant.ofEpochMilli(createdAt) + "; updated=" + Instant.ofEpochMilli(updatedAt) + "; detail=" + detail;
    }
}
