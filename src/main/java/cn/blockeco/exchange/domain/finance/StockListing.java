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
        if (!isValidStockCode(stockCode)) throw new IllegalArgumentException("stockCode must be a player BS code or an uppercase bluechip ticker");
        Objects.requireNonNull(issueReferencePrice, "issueReferencePrice");
        if (issueReferencePrice.minorUnits() <= 0 || issuedShares <= 0) {
            throw new IllegalArgumentException("issue reference price and issued shares must be positive");
        }
        Objects.requireNonNull(listedAt, "listedAt");
    }

    public static boolean isValidStockCode(String stockCode) {
        if (stockCode == null) return false;
        if (stockCode.matches("BS[0-9]+")) return stockCode.matches("BS[0-9]{6}") && Long.parseLong(stockCode.substring(2)) > 0;
        return stockCode.matches("[A-Z][A-Z0-9]{1,11}");
    }
}
