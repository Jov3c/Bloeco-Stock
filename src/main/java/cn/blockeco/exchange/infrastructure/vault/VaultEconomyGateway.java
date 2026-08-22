package cn.blockeco.exchange.infrastructure.vault;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.EconomyGateway;
import java.math.BigDecimal;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Synchronous adapter: callers must invoke this only through MainThreadExecutor. */
public final class VaultEconomyGateway implements EconomyGateway {
    private final Server server; private final int currencyScale;
    public VaultEconomyGateway(Server server, int currencyScale) { this.server = server; this.currencyScale = currencyScale; }
    @Override public Result withdraw(UUID playerId, Money amount) { return invoke(playerId, amount, true); }
    @Override public Result deposit(UUID playerId, Money amount) { return invoke(playerId, amount, false); }
    @Override public Money balance(UUID playerId) {
        try {
            RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
            if (registration == null || registration.getProvider() == null) throw new IllegalStateException("no Vault economy provider registered");
            double value = registration.getProvider().getBalance(server.getOfflinePlayer(playerId));
            if (!Double.isFinite(value)) throw new IllegalStateException("Vault returned non-finite balance");
            return Money.fromMajor(BigDecimal.valueOf(value), currencyScale);
        } catch (RuntimeException exception) { throw new IllegalStateException("could not read Vault balance", exception); }
    }
    private Result invoke(UUID playerId, Money amount, boolean withdrawal) {
        Economy provider; OfflinePlayer player; double value;
        try {
            BigDecimal major = amount.toMajor(currencyScale);
            value = major.doubleValue();
            if (!Double.isFinite(value) || BigDecimal.valueOf(value).compareTo(major) != 0) return Result.notCalledFailure("amount cannot round-trip through Vault double");
            RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
            if (registration == null || registration.getProvider() == null) return Result.notCalledFailure("no Vault economy provider registered");
            player = server.getOfflinePlayer(playerId);
            provider = registration.getProvider();
            if (withdrawal && !provider.has(player, value)) {
                return Result.insufficientFunds("provider reports insufficient funds");
            }
        } catch (RuntimeException exception) { return Result.notCalledFailure(exception.getMessage()); }
        try {
            EconomyResponse response = withdrawal ? provider.withdrawPlayer(player, value) : provider.depositPlayer(player, value);
            if (response != null && response.transactionSuccess()) return Result.success(response.errorMessage);
            String message = response == null ? "Vault economy returned no response" : response.errorMessage;
            return Result.providerFailure(message);
        } catch (RuntimeException exception) { return Result.providerFailure(exception.getMessage()); }
    }
}
