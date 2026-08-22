package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Private fill projection; counterparty/order identities are intentionally omitted. */
public record TradeView(UUID id, String stockCode, LimitOrder.Side side, long shares, Money price, Money notional, Money fee, Instant occurredAt) {
    public TradeView { Objects.requireNonNull(id); Objects.requireNonNull(stockCode); Objects.requireNonNull(side); Objects.requireNonNull(price); Objects.requireNonNull(notional); Objects.requireNonNull(fee); Objects.requireNonNull(occurredAt); }
}
