package cn.blockeco.exchange.domain.governance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CompanyLiquidationClaim(UUID actionId, UUID holderUuid, long shares, long entitlementMinor,
        long companyContributionMinor, long fundContributionMinor, LiquidationClaimState state, Instant createdAt, Instant updatedAt) {
    public CompanyLiquidationClaim {
        Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(holderUuid, "holderUuid"); Objects.requireNonNull(state, "state"); Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (shares < 0 || entitlementMinor < 0 || companyContributionMinor < 0 || fundContributionMinor < 0) throw new IllegalArgumentException("claim values cannot be negative");
        if (Math.addExact(companyContributionMinor, fundContributionMinor) != entitlementMinor) throw new IllegalArgumentException("claim contributions must equal entitlement");
    }
}
