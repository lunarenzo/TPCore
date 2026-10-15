package com.lunatech.tpcore.module.warp.model;

import java.util.List;

/** Immutable observations; concurrent activity can change immediately after a snapshot. */
public record WarpDiagnostics(String storage, boolean ready, int warps, int warpLimit, int cacheCapacity,
    int pendingOperations, int operationCapacity, int activeTeleports, int pendingMutations,
    boolean migrating, Economy economy) {

    public record Economy(boolean available, boolean journalReady, boolean reconciliationRequired,
        int activeOperations, int retainedIntents, int intentCapacity) {}

    /** Evidence is deliberately raw: an intent does not prove a debit or credit completed. */
    public record PaymentIntent(String ticket, String evidence) {}

    public record PaymentPage(int page, int pages, int total, List<PaymentIntent> entries) {
        public PaymentPage { entries = List.copyOf(entries); }
    }
}
