package com.lunatech.tpcore.module.home.repository;

import com.lunatech.tpcore.module.home.model.Home;
import com.lunatech.tpcore.module.home.model.HomePage;
import com.lunatech.tpcore.module.home.model.HomePayment;
import com.lunatech.tpcore.module.home.service.HomeResultStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface HomeRepository {
    record SaveCheck(HomeResultStatus status, HomePage.Entry existing) {}

    CompletableFuture<Void> initialize();
    CompletableFuture<Optional<Home>> findByName(UUID owner, String name);
    CompletableFuture<Optional<Home>> findMatching(HomePage.Entry expected);
    CompletableFuture<HomePage> page(UUID owner, long page);
    CompletableFuture<List<Home>> list(UUID owner, int limit, long offset);
    CompletableFuture<List<String>> suggestNames(UUID owner, String prefix, int limit);
    CompletableFuture<HomeResultStatus> save(Home home, boolean overwrite, int limit);
    CompletableFuture<SaveCheck> checkSave(Home home, boolean overwrite, int limit);
    CompletableFuture<HomeResultStatus> saveChecked(Home home, HomePage.Entry expected, int limit, UUID payment, int historyLimit);
    CompletableFuture<HomeResultStatus> beginPayment(HomePayment payment, int capacity);
    CompletableFuture<Boolean> updatePayment(UUID id, HomePayment.State expected, HomePayment.State next,
        BigDecimal debit, BigDecimal refund, int historyLimit);
    CompletableFuture<List<HomePayment>> listPayments(long offset);
    CompletableFuture<Optional<HomePayment>> findPayment(UUID id);
    CompletableFuture<Boolean> acknowledgePayment(UUID id, String operator, int historyLimit);
    CompletableFuture<HomeResultStatus> archive(UUID owner, String name, int capacity, long retentionMillis);
    CompletableFuture<HomeResultStatus> archiveMatching(HomePage.Entry expected, int capacity, long retentionMillis);
    CompletableFuture<HomeResultStatus> restore(UUID owner, String name, int limit, long retentionMillis);
    CompletableFuture<Void> close();
    void settlementDrain(CompletableFuture<Void> drain);
}
