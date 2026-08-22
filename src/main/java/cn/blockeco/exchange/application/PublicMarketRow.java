package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

/** Public, aggregate-only view of a listed company. */
public record PublicMarketRow(String companyName, String stockCode, Money issueReferencePrice,
                              Money marketCapitalization, long issuedShares, CompanyStatus status,
                              Money latestPrice, Money change, long volume, Money turnover) {
    public PublicMarketRow { Objects.requireNonNull(companyName); Objects.requireNonNull(stockCode); Objects.requireNonNull(issueReferencePrice); Objects.requireNonNull(marketCapitalization); Objects.requireNonNull(status); Objects.requireNonNull(latestPrice); Objects.requireNonNull(change); Objects.requireNonNull(turnover); if(volume<0)throw new IllegalArgumentException("volume must be non-negative"); }
    public PublicMarketRow(String companyName,String stockCode,Money issueReferencePrice,Money marketCapitalization,long issuedShares,CompanyStatus status) { this(companyName,stockCode,issueReferencePrice,marketCapitalization,issuedShares,status,issueReferencePrice,Money.zero(),0,Money.zero()); }
}
