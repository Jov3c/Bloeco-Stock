package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

/** Read-only reconciliation; uncertain external effects are never netted into confirmed totals. */
public record EscrowReconciliation(
        Money physicalBalance,
        Money companyTreasuryLiability,
        Money securitiesCashLiability,
        Money compensationFundLiability,
        Money provenInboundNotYetLiability,
        Money provenOutboundStillLiability,
        Money uncertainExternalAmount) {
    public EscrowReconciliation {
        Objects.requireNonNull(physicalBalance, "physicalBalance").requireNonNegative("physicalBalance");
        Objects.requireNonNull(companyTreasuryLiability, "companyTreasuryLiability").requireNonNegative("companyTreasuryLiability");
        Objects.requireNonNull(securitiesCashLiability, "securitiesCashLiability").requireNonNegative("securitiesCashLiability");
        Objects.requireNonNull(compensationFundLiability, "compensationFundLiability").requireNonNegative("compensationFundLiability");
        Objects.requireNonNull(provenInboundNotYetLiability, "provenInboundNotYetLiability").requireNonNegative("provenInboundNotYetLiability");
        Objects.requireNonNull(provenOutboundStillLiability, "provenOutboundStillLiability").requireNonNegative("provenOutboundStillLiability");
        Objects.requireNonNull(uncertainExternalAmount, "uncertainExternalAmount").requireNonNegative("uncertainExternalAmount");
    }

    public Money finalLiabilities() {
        return companyTreasuryLiability.plus(securitiesCashLiability).plus(compensationFundLiability);
    }

    public Money confirmedExpectedBalance() {
        return finalLiabilities().plus(provenInboundNotYetLiability).minus(provenOutboundStillLiability);
    }

    /** Positive means the physical escrow contains more than proven internal obligations. */
    public Money confirmedDifference() {
        return physicalBalance.minus(confirmedExpectedBalance());
    }
}
