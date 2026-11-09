package com.lunatech.tpcore.module.home.model;

import java.math.BigDecimal;
import java.util.UUID;

public record HomePayment(UUID id, UUID player, String home, Action action, String provider, BigDecimal requested,
    BigDecimal debit, BigDecimal refund, State state, long updated, String operator) {

    public enum Action { TRAVEL, CREATE }

    public enum State {
        DEBIT_PENDING, DEBITED, TELEPORT_PENDING, REFUND_PENDING, REVIEW,
        REJECTED, KEPT, REFUNDED, ACKNOWLEDGED;

        public boolean resolved() {
            return this == REJECTED || this == KEPT || this == REFUNDED || this == ACKNOWLEDGED;
        }
    }
}
