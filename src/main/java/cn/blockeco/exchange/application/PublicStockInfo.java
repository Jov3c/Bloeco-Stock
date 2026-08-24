package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.Optional;

/** Safe company facts for the public stock command. */
public record PublicStockInfo(String companyName, Optional<String> stockCode, CompanyStatus status,
                              Optional<Money> issueReferencePrice, long issuedShares, Optional<PublicMarketState> marketState) {
    public PublicStockInfo { Objects.requireNonNull(companyName); Objects.requireNonNull(stockCode); Objects.requireNonNull(status); Objects.requireNonNull(issueReferencePrice); Objects.requireNonNull(marketState); }
    public PublicStockInfo(String companyName, Optional<String> stockCode, CompanyStatus status, Optional<Money> issueReferencePrice, long issuedShares) { this(companyName,stockCode,status,issueReferencePrice,issuedShares,Optional.empty()); }
}
