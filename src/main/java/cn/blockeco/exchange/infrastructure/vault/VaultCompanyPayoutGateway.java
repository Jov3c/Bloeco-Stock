package cn.blockeco.exchange.infrastructure.vault;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.CompanyPayoutGateway;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.Objects;
import java.util.UUID;

/** One-way company-to-founder Vault deposit. Ambiguous provider calls are deliberately never replayed. */
public final class VaultCompanyPayoutGateway implements CompanyPayoutGateway {
    private final EconomyGateway economy; private final MainThreadExecutor main;
    public VaultCompanyPayoutGateway(EconomyGateway economy, MainThreadExecutor main) { this.economy=Objects.requireNonNull(economy); this.main=Objects.requireNonNull(main); }
    @Override public Result depositFounder(UUID recipient, Money amount, UUID operationId) {
        try {
            EconomyGateway.Result result = main.submit(() -> economy.deposit(recipient, amount)).toCompletableFuture().join();
            if (result != null && result.outcome() == EconomyGateway.Outcome.SUCCESS) return Result.success("Vault 入账已确认");
            // A provider that was not invoked has a known no-payment result. A called failure may have paid.
            if (result != null && !result.providerWasCalled()) return Result.knownFailure("Vault 未执行入账");
            return Result.unknown("Vault 入账结果无法确认");
        } catch (RuntimeException failure) { return Result.unknown("Vault 入账结果无法确认"); }
    }
}
