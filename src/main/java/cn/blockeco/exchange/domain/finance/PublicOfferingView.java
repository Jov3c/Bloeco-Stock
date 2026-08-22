package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public, immutable IPO read model. It intentionally contains no subscriber, escrow, or saga detail. */
public record PublicOfferingView(UUID offeringId, CompanyId companyId, String companyDisplayName,
        PrimaryOfferingState state, Money target, Money issuePrice, long maximumShares,
        long issuedShares, long reservedShares, long availableShares, Instant announcedAt,
        Instant opensAt, Instant closesAt) {
    public PublicOfferingView {
        Objects.requireNonNull(offeringId); Objects.requireNonNull(companyId); Objects.requireNonNull(companyDisplayName);
        Objects.requireNonNull(state); Objects.requireNonNull(target); Objects.requireNonNull(issuePrice);
        Objects.requireNonNull(announcedAt); Objects.requireNonNull(opensAt); Objects.requireNonNull(closesAt);
        if (maximumShares < 0 || issuedShares < 0 || reservedShares < 0 || issuedShares > reservedShares || reservedShares > maximumShares)
            throw new IllegalArgumentException("invalid public offering share totals");
        if (availableShares != maximumShares - reservedShares || availableShares < 0)
            throw new IllegalArgumentException("invalid available shares");
    }
}
