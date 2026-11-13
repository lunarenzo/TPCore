package com.lunatech.tpcore.module.rtp.economy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

final class VaultRtpAdapter {
    record Receipt(boolean successful, BigDecimal amount) {}

    private final Class<?> economy;
    private final Method async, balance, withdraw, deposit, successful;
    private final Field amount;

    VaultRtpAdapter(ClassLoader loader) throws ReflectiveOperationException {
        economy = Class.forName("net.milkbowl.vault2.economy.Economy", false, loader);
        Class<?> nativeApi = Class.forName("net.milkbowl.vault2.economy.AsyncEconomy", false, loader);
        Class<?> response = Class.forName("net.milkbowl.vault2.economy.EconomyResponse", false, loader);
        async = economy.getMethod("async");
        balance = nativeApi.getMethod("balance", String.class, UUID.class);
        withdraw = nativeApi.getMethod("withdraw", String.class, UUID.class, BigDecimal.class);
        deposit = nativeApi.getMethod("deposit", String.class, UUID.class, BigDecimal.class);
        successful = response.getMethod("transactionSuccess");
        amount = response.getField("amount");
    }

    private RegisteredServiceProvider<?> registration() {
        return Bukkit.getServicesManager().getRegistration(economy);
    }

    Provider provider() throws ReflectiveOperationException {
        var registration = registration();
        if (registration == null) return null;
        var nativeApi = (Optional<?>) async.invoke(registration.getProvider());
        return nativeApi.isEmpty() ? null : new Provider(registration, nativeApi.get());
    }

    final class Provider implements RtpEconomy.Bank {
        private final RegisteredServiceProvider<?> registration;
        private final Object nativeApi;

        Provider(RegisteredServiceProvider<?> registration, Object nativeApi) {
            this.registration = registration;
            this.nativeApi = nativeApi;
        }

        @Override public String identity() {
            return registration.getPlugin().getName() + "/" + registration.getProvider().getClass().getName();
        }

        @Override public boolean current() { return registration() == registration; }

        @Override public CompletableFuture<BigDecimal> balance(String plugin, UUID player) {
            return call(balance, plugin, player).thenApply(BigDecimal.class::cast);
        }

        @Override public CompletableFuture<Receipt> transfer(boolean refund, String plugin, UUID player, BigDecimal value) {
            return call(refund ? deposit : withdraw, plugin, player, value).thenApply(response -> {
                try {
                    BigDecimal changed = (BigDecimal) amount.get(response);
                    if (changed == null || changed.signum() < 0 || changed.precision() > 64 || Math.abs((long) changed.scale()) > 64)
                        throw new IllegalStateException("Invalid RTP payment receipt");
                    return new Receipt(Boolean.TRUE.equals(successful.invoke(response)), changed);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Invalid RTP payment receipt", failure);
                }
            });
        }

        private CompletableFuture<?> call(Method method, Object... arguments) {
            try {
                return (CompletableFuture<?>) method.invoke(nativeApi, arguments);
            } catch (ReflectiveOperationException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }
}
