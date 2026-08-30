package cn.blockeco.exchange.domain.governance;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CompanyPayoutOperation(UUID id, CompanyId companyId, UUID governanceActionId, UUID recipientUuid,
        long amountMinor, String correlationKey, PayoutOperationState state, Instant createdAt, Instant updatedAt, String detail) {
    public CompanyPayoutOperation {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(companyId, "companyId"); Objects.requireNonNull(governanceActionId, "governanceActionId");
        Objects.requireNonNull(recipientUuid, "recipientUuid"); Objects.requireNonNull(correlationKey, "correlationKey"); Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (amountMinor <= 0) throw new IllegalArgumentException("payout amount must be positive");
        if (correlationKey.isBlank()) throw new IllegalArgumentException("correlation key is required");
    }
}
