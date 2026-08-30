package cn.blockeco.exchange.domain.governance;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CompanyGovernanceAction(UUID id, CompanyId companyId, UUID actorUuid, GovernanceActionType type,
        long amountMinor, long pricePerShareMinor, Instant announcedAt, Instant executableAt,
        GovernanceActionState state, String correlationKey) {
    public CompanyGovernanceAction {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(companyId, "companyId"); Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(type, "type"); Objects.requireNonNull(announcedAt, "announcedAt"); Objects.requireNonNull(executableAt, "executableAt");
        Objects.requireNonNull(state, "state"); Objects.requireNonNull(correlationKey, "correlationKey");
        if (amountMinor < 0 || pricePerShareMinor < 0) throw new IllegalArgumentException("amounts cannot be negative");
        if (executableAt.isBefore(announcedAt)) throw new IllegalArgumentException("action cannot execute before announcement");
        if (correlationKey.isBlank()) throw new IllegalArgumentException("correlation key is required");
    }
}
