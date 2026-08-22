package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

/** A player's own holding; it deliberately contains no other player identity. */
public record SecondaryMarketRow(String companyName, String stockCode, long availableShares, long reservedShares, Money latestPrice) {
    public SecondaryMarketRow { Objects.requireNonNull(companyName); Objects.requireNonNull(stockCode); Objects.requireNonNull(latestPrice); if (availableShares < 0 || reservedShares < 0) throw new IllegalArgumentException("shares must be non-negative"); }
}
