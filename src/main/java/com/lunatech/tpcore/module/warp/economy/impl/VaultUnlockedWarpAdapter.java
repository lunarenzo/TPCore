package com.lunatech.tpcore.module.warp.economy.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Optional native async capability: newer Vault classes are never linked into shared code. */
final class VaultUnlockedWarpAdapter {
    record Receipt(boolean successful, BigDecimal amount, BigDecimal balance, String error) {}
    private final Object provider;
    private Object registration;
    private final Method withdraw, deposit, balance, successful;
    private final Field amount, responseBalance, error;
    private String identity;
    private volatile boolean active = true;

    VaultUnlockedWarpAdapter(Object provider, Class<?> api, Class<?> response) throws ReflectiveOperationException {
        this.provider = provider; registration = provider;
        identity = provider.getClass().getName() + "/" + UUID.randomUUID();
        withdraw = api.getMethod("withdraw", String.class, UUID.class, BigDecimal.class);
        deposit = api.getMethod("deposit", String.class, UUID.class, BigDecimal.class);
        balance = api.getMethod("balance", String.class, UUID.class);
        successful = response.getMethod("transactionSuccess"); amount = response.getField("amount");
        responseBalance = optionalField(response, "balance"); error = optionalField(response, "errorMessage");
    }
    private static Field optionalField(Class<?> type, String name) {
        try { return type.getField(name); } catch (NoSuchFieldException absent) { return null; }
    }
    String identity() { return identity; }
    boolean active() { return active; }
    boolean sameProvider(VaultUnlockedWarpAdapter other) { return other != null && registration == other.registration; }
    void close() { active = false; } // Never cancel a provider-owned bank transfer.

    static Optional<VaultUnlockedWarpAdapter> discover(ClassLoader loader) {
        try {
            Class<?> economy = Class.forName("net.milkbowl.vault2.economy.Economy", false, loader);
            RegisteredServiceProvider<?> entry = Bukkit.getServicesManager().getRegistration(economy);
            if (entry == null || !entry.getPlugin().isEnabled()) return Optional.empty();
            Object service = entry.getProvider();
            Optional<?> nativeApi = (Optional<?>) economy.getMethod("async").invoke(service);
            if (nativeApi.isEmpty()) return Optional.empty();
            Class<?> api = Class.forName("net.milkbowl.vault2.economy.AsyncEconomy", false, loader);
            Class<?> response = Class.forName("net.milkbowl.vault2.economy.EconomyResponse", false, loader);
            VaultUnlockedWarpAdapter result = new VaultUnlockedWarpAdapter(nativeApi.get(), api, response);
            result.registration = service;
            result.identity = entry.getPlugin().getName() + "/" + result.identity;
            return Optional.of(result);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return Optional.empty(); }
    }
    private CompletableFuture<?> invoke(Method method, Object... arguments) {
        if (!active) return CompletableFuture.failedFuture(new IllegalStateException("Original economy provider is unavailable"));
        try { return (CompletableFuture<?>) method.invoke(provider, arguments); }
        catch (ReflectiveOperationException | RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private Receipt receipt(Object response) {
        try {
            BigDecimal changed = (BigDecimal) amount.get(response);
            if (changed == null || changed.signum() < 0 || changed.precision() > 384 || Math.abs((long) changed.scale()) > 384)
                throw new IllegalStateException("Invalid native economy amount");
            String reason = error == null ? "" : String.valueOf(error.get(response));
            return new Receipt(Boolean.TRUE.equals(successful.invoke(response)), changed,
                responseBalance == null ? null : (BigDecimal) responseBalance.get(response), reason.substring(0, Math.min(256, reason.length())));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Invalid native economy receipt", failure); }
    }
    CompletableFuture<Receipt> withdrawReceipt(String plugin, UUID player, BigDecimal cost) { return invoke(withdraw, plugin, player, cost).thenApply(this::receipt); }
    CompletableFuture<Receipt> depositReceipt(String plugin, UUID player, BigDecimal cost) { return invoke(deposit, plugin, player, cost).thenApply(this::receipt); }
    CompletableFuture<BigDecimal> balance(String plugin, UUID player) { return invoke(balance, plugin, player).thenApply(value -> (BigDecimal) value); }

    // Existing convenience API; production settlement consumes decimal receipts above.
    CompletableFuture<Double> withdraw(String plugin, UUID player, double cost) {
        return withdrawReceipt(plugin, player, BigDecimal.valueOf(cost)).thenApply(value -> value.successful() ? value.amount().doubleValue() : -1.0);
    }
    CompletableFuture<Boolean> deposit(String plugin, UUID player, double cost) {
        return depositReceipt(plugin, player, BigDecimal.valueOf(cost)).thenApply(value -> value.successful() && value.amount().compareTo(BigDecimal.valueOf(cost)) == 0);
    }
    CompletableFuture<Boolean> has(String plugin, UUID player, double cost) { return balance(plugin, player).thenApply(value -> value.compareTo(BigDecimal.valueOf(cost)) >= 0); }
}
