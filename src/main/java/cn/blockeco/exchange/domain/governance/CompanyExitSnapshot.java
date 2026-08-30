package cn.blockeco.exchange.domain.governance;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable ownership fact captured only after all exit order reservations are released. */
public record CompanyExitSnapshot(UUID actionId, CompanyId companyId, UUID holderUuid, long availableShares,
        long reservedShares, Instant snapshottedAt) {
    public CompanyExitSnapshot {
        Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(companyId, "companyId"); Objects.requireNonNull(holderUuid, "holderUuid"); Objects.requireNonNull(snapshottedAt, "snapshottedAt");
        if (availableShares < 0 || reservedShares < 0) throw new IllegalArgumentException("snapshot shares cannot be negative");
    }
    public long totalShares() { return Math.addExact(availableShares, reservedShares); }
}
