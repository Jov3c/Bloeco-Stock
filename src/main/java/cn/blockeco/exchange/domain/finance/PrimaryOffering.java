package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PrimaryOffering(
        UUID id,
        CompanyId companyId,
        Money target,
        Money issuePrice,
        long maximumShares,
        Instant announcedAt,
        Instant opensAt,
        Instant closesAt,
        PrimaryOfferingState state) {

    private static final Duration ANNOUNCEMENT_WINDOW = Duration.ofHours(12);
    private static final Duration OFFERING_WINDOW = Duration.ofDays(2);

    public PrimaryOffering {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(target, "target").requireNonNegative("target");
        Objects.requireNonNull(issuePrice, "issuePrice").requireNonNegative("issuePrice");
        if (target.minorUnits() == 0 || issuePrice.minorUnits() == 0 || maximumShares <= 0) {
            throw new IllegalArgumentException("offering target, price, and maximum shares must be positive");
        }
        Objects.requireNonNull(announcedAt, "announcedAt");
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        if (opensAt.isBefore(announcedAt) || !closesAt.isAfter(opensAt)) {
            throw new IllegalArgumentException("offering times must be ordered");
        }
        Objects.requireNonNull(state, "state");
    }

    public static PrimaryOffering plan(CompanyId companyId, Money target, Money issuePrice, Instant announcedAt) {
        Objects.requireNonNull(target, "target").requireNonNegative("target");
        Objects.requireNonNull(issuePrice, "issuePrice").requireNonNegative("issuePrice");
        if (target.minorUnits() == 0 || issuePrice.minorUnits() == 0) {
            throw new IllegalArgumentException("target and issuePrice must be positive");
        }
        long maximumShares = target.minorUnits() / issuePrice.minorUnits();
        if (maximumShares == 0) {
            throw new IllegalArgumentException("target must fund at least one share");
        }
        Instant opensAt = Objects.requireNonNull(announcedAt, "announcedAt").plus(ANNOUNCEMENT_WINDOW);
        return new PrimaryOffering(
                UUID.randomUUID(), companyId, target, issuePrice, maximumShares, announcedAt, opensAt,
                opensAt.plus(OFFERING_WINDOW), PrimaryOfferingState.ANNOUNCED);
    }
}
