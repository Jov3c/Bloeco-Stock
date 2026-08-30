package cn.blockeco.exchange.infrastructure.vault;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.CompanyPayoutGateway;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.TreasuryEscrowGateway;
import java.util.Objects;
import java.util.UUID;

/** One-way company-to-founder Vault deposit. Ambiguous provider calls are deliberately never replayed. */
public final class VaultCompanyPayoutGateway implements CompanyPayoutGateway {
    private final TreasuryEscrowGateway escrow;
    public VaultCompanyPayoutGateway(TreasuryEscrowGateway escrow) { this.escrow=Objects.requireNonNull(escrow); }
    @Override public Result depositFounder(UUID recipient, Money amount, UUID operationId) {
        try {
            EconomyGateway.Result debit = escrow.withdrawEscrow(amount, operationId);
            if (debit == null || debit.outcome() != EconomyGateway.Outcome.SUCCESS) return knownOrUnknown(debit);
            EconomyGateway.Result credit = escrow.refundPlayer(recipient, amount, operationId);
            if (credit != null && credit.outcome() == EconomyGateway.Outcome.SUCCESS) return Result.success("Vault 托管扣款及入账已确认");
            return Result.unknown("Vault 入账结果无法确认");
        } catch (RuntimeException failure) { return Result.unknown("Vault 入账结果无法确认"); }
    }
    private static Result knownOrUnknown(EconomyGateway.Result result) { return result != null && !result.providerWasCalled() ? Result.knownFailure("Vault 未执行托管扣款") : Result.unknown("Vault 托管扣款结果无法确认"); }
}
