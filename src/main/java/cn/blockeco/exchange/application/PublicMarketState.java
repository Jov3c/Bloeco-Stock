package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.Optional;

/** Local model state shown by the market detail UI; no player balances are exposed. */
public record PublicMarketState(Money modelPrice, boolean liquidityDegraded, Optional<MarketNewsItem> currentEvent) {
    public PublicMarketState { Objects.requireNonNull(modelPrice); Objects.requireNonNull(currentEvent); }
}
