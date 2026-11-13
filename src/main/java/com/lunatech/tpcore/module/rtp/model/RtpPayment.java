package com.lunatech.tpcore.module.rtp.model;

import java.math.BigDecimal;
import java.util.UUID;

public record RtpPayment(UUID id, UUID player, String world, String action, String provider, BigDecimal requested,
    BigDecimal debit, BigDecimal refund, State state, long updated, String operator) {
    public enum State {
        DEBIT_PENDING, TELEPORT_PENDING, REFUND_PENDING, DEBIT_UNKNOWN, TELEPORT_UNKNOWN, REFUND_UNKNOWN,
        REJECTED, KEPT, REFUNDED, ACKNOWLEDGED;
        public boolean resolved() { return this == REJECTED || this == KEPT || this == REFUNDED || this == ACKNOWLEDGED; }
    }
}
