package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Private order projection. */
public record OrderView(UUID id, String stockCode, LimitOrder.Side side, Money limitPrice, long originalShares, long remainingShares, Money reservedCash, Instant acceptedAt, LimitOrder.State state) {
    public OrderView { Objects.requireNonNull(id); Objects.requireNonNull(stockCode); Objects.requireNonNull(side); Objects.requireNonNull(limitPrice); Objects.requireNonNull(reservedCash); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(state); }
}
