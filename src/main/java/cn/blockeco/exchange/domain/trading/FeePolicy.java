package cn.blockeco.exchange.domain.trading;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

public final class FeePolicy {
    private static final long BASIS_POINTS = 10_000L;

    private FeePolicy() {}

    public static Money cumulativeFee(Money notional, int feeBps) {
        Objects.requireNonNull(notional, "notional").requireNonNegative("notional");
        if (feeBps < 0 || feeBps > BASIS_POINTS) {
            throw new IllegalArgumentException("feeBps must be between 0 and 10000");
        }
        long numerator = Math.multiplyExact(notional.minorUnits(), feeBps);
        long rounded = Math.addExact(numerator, BASIS_POINTS - 1) / BASIS_POINTS;
        return Money.ofMinor(rounded);
    }
}
