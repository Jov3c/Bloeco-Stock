package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.trading.LimitOrder;
import java.util.Objects;

/** The committed terminal/current state of one submitted or cancelled order. */
public record OrderPlacementResult(LimitOrder order) {
    public OrderPlacementResult { Objects.requireNonNull(order, "order"); }
}
