package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;

/** Immutable public-market identity and IPO-derived initial listing terms. */
public record StockListing(
        CompanyId companyId,
        String stockCode,
        Money issueReferencePrice,
        long issuedShares,
        Instant listedAt) {

    public StockListing {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(stockCode, "stockCode");
        if (!stockCode.matches("BS[0-9]{6}")
                || Long.parseLong(stockCode.substring(2)) == 0) {
            throw new IllegalArgumentException("stockCode must be BS followed by six digits from 000001 to 999999");
        }
        Objects.requireNonNull(issueReferencePrice, "issueReferencePrice");
        if (issueReferencePrice.minorUnits() <= 0 || issuedShares <= 0) {
            throw new IllegalArgumentException("issue reference price and issued shares must be positive");
        }
        Objects.requireNonNull(listedAt, "listedAt");
    }
}
