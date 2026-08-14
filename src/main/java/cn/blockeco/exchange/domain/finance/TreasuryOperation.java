package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TreasuryOperation(
        UUID id,
        CompanyId companyId,
        UUID playerId,
        Money amount,
        String providerCorrelationKey,
        TreasuryOperationState state,
        Instant createdAt,
        Instant updatedAt) {

    public TreasuryOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(amount, "amount").requireNonNegative("amount");
        if (providerCorrelationKey == null || providerCorrelationKey.isBlank()) {
            throw new IllegalArgumentException("providerCorrelationKey must not be blank");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
