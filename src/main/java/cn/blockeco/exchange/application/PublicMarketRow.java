package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

/** Public, aggregate-only view of a listed company. */
public record PublicMarketRow(String companyName, String stockCode, Money issueReferencePrice,
                              Money marketCapitalization, long issuedShares, CompanyStatus status) {
    public PublicMarketRow { Objects.requireNonNull(companyName); Objects.requireNonNull(stockCode); Objects.requireNonNull(issueReferencePrice); Objects.requireNonNull(marketCapitalization); Objects.requireNonNull(status); }
}
