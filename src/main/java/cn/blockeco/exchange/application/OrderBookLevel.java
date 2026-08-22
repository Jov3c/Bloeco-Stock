package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

/** Anonymous aggregate at one price. */
public record OrderBookLevel(Money price, long shares) { public OrderBookLevel { Objects.requireNonNull(price); if (shares <= 0) throw new IllegalArgumentException("shares must be positive"); } }
