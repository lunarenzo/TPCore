package com.lunatech.tpcore.module.warp.economy.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;

/** Optional VaultUnlocked 2.20+ capability. No newer API type is linked into the plugin. */
final class VaultUnlockedWarpAdapter {
    private final Object provider;
    private final Method withdraw;
    private final Method deposit;
    private final Method balance;
    private final Method successful;
    private final Field amount;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    VaultUnlockedWarpAdapter(Object provider, Class<?> api, Class<?> response) throws ReflectiveOperationException {
        this.provider = provider;
        withdraw = api.getMethod("withdraw", String.class, UUID.class, BigDecimal.class);
        deposit = api.getMethod("deposit", String.class, UUID.class, BigDecimal.class);
        balance = api.getMethod("balance", String.class, UUID.class);
        successful = response.getMethod("transactionSuccess");
        amount = response.getField("amount");
    }

    static Optional<VaultUnlockedWarpAdapter> discover(ClassLoader loader) {
        try {
            Class<?> economy = Class.forName("net.milkbowl.vault2.economy.Economy", false, loader);
            Object service = Bukkit.getServicesManager().load(economy);
            if (service == null) return Optional.empty();
            Optional<?> nativeApi = (Optional<?>) economy.getMethod("async").invoke(service);
            if (nativeApi.isEmpty()) return Optional.empty();
            Class<?> api = Class.forName("net.milkbowl.vault2.economy.AsyncEconomy", false, loader);
            Class<?> response = Class.forName("net.milkbowl.vault2.economy.EconomyResponse", false, loader);
            return Optional.of(new VaultUnlockedWarpAdapter(nativeApi.get(), api, response));
        } catch (ReflectiveOperationException | LinkageError failure) {
            return Optional.empty();
        }
    }

    private CompletableFuture<?> invoke(Method method, Object... arguments) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Warp economy adapter is closed"));
        try {
            CompletableFuture<?> future = (CompletableFuture<?>) method.invoke(provider, arguments);
            pending.add(future);
            future.whenComplete((value, failure) -> pending.remove(future));
            if (closed.get()) future.cancel(false);
            return future;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    void close() {
        closed.set(true);
        // Cancel observation, not the underlying bank transfer. Its persisted intent requires reconciliation.
        pending.forEach(future -> future.cancel(false));
        pending.clear();
    }

    CompletableFuture<Double> withdraw(String pluginName, UUID playerId, double cost) {
        return invoke(withdraw, pluginName, playerId, BigDecimal.valueOf(cost)).thenApply(response -> {
            try {
                if (!Boolean.TRUE.equals(successful.invoke(response))) return -1.0;
                double paid = ((BigDecimal) amount.get(response)).doubleValue();
                if (!Double.isFinite(paid) || paid < 0) throw new IllegalStateException("Invalid native economy amount");
                return paid;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Invalid native economy receipt", failure);
            }
        });
    }

    CompletableFuture<Boolean> deposit(String pluginName, UUID playerId, double cost) {
        return invoke(deposit, pluginName, playerId, BigDecimal.valueOf(cost)).thenApply(response -> {
            try {
                return Boolean.TRUE.equals(successful.invoke(response))
                    && ((BigDecimal) amount.get(response)).compareTo(BigDecimal.valueOf(cost)) == 0;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Invalid native economy receipt", failure);
            }
        });
    }

    CompletableFuture<Boolean> has(String pluginName, UUID playerId, double cost) {
        return invoke(balance, pluginName, playerId).thenApply(value -> ((BigDecimal) value).compareTo(BigDecimal.valueOf(cost)) >= 0);
    }
}
