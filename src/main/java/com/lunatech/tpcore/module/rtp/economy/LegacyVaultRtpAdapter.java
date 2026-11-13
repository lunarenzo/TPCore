package com.lunatech.tpcore.module.rtp.economy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/** The configured provider must support calling its classic Vault methods off the tick thread. */
final class LegacyVaultRtpAdapter implements RtpEconomy.Bank {
    private final Plugin plugin;
    private final Executor worker;
    private final int timeoutSeconds;
    private final RegisteredServiceProvider<Economy> registration;

    private LegacyVaultRtpAdapter(Plugin plugin, Executor worker, int timeoutSeconds,
        RegisteredServiceProvider<Economy> registration) {
        this.plugin = plugin;
        this.worker = worker;
        this.timeoutSeconds = timeoutSeconds;
        this.registration = registration;
    }

    static LegacyVaultRtpAdapter discover(Plugin plugin, Executor worker, int timeoutSeconds) {
        var entry = registration();
        return entry == null ? null : new LegacyVaultRtpAdapter(plugin, worker, timeoutSeconds, entry);
    }

    private static RegisteredServiceProvider<Economy> registration() {
        return Bukkit.getServicesManager().getRegistration(Economy.class);
    }

    @Override public String identity() {
        return registration.getPlugin().getName() + "/" + registration.getProvider().getClass().getName();
    }

    @Override public boolean current() { return registration() == registration; }

    @Override public CompletableFuture<BigDecimal> balance(String requester, UUID player) {
        return onAccount(player, account -> BigDecimal.valueOf(registration.getProvider().getBalance(account)));
    }

    @Override public CompletableFuture<VaultRtpAdapter.Receipt> transfer(boolean refund, String requester, UUID player, BigDecimal amount) {
        return onAccount(player, account -> {
            Economy provider = registration.getProvider();
            double value = amount.doubleValue();
            int digits = provider.fractionalDigits();
            if (!Double.isFinite(value) || value <= 0 || BigDecimal.valueOf(value).compareTo(amount) != 0
                || digits < -1 || (digits >= 0 && amount.stripTrailingZeros().scale() > digits)) {
                return new VaultRtpAdapter.Receipt(false, BigDecimal.ZERO);
            }
            var response = refund ? provider.depositPlayer(account, value) : provider.withdrawPlayer(account, value);
            if (response == null || !Double.isFinite(response.amount) || response.amount < 0)
                throw new IllegalStateException("Invalid legacy RTP payment receipt");
            return new VaultRtpAdapter.Receipt(response.transactionSuccess(), BigDecimal.valueOf(response.amount));
        });
    }

    private <T> CompletableFuture<T> onAccount(UUID player, Function<OfflinePlayer, T> operation) {
        var account = new CompletableFuture<OfflinePlayer>().orTimeout(timeoutSeconds, TimeUnit.SECONDS);
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                if (account.isDone()) return;
                try { account.complete(Bukkit.getOfflinePlayer(player)); }
                catch (RuntimeException failure) { account.completeExceptionally(failure); }
            });
        } catch (RuntimeException retired) {
            account.completeExceptionally(retired);
        }
        return account.thenApplyAsync(handle -> {
            if (!current()) throw new IllegalStateException("Legacy RTP economy registration changed");
            return operation.apply(handle);
        }, worker);
    }
}
