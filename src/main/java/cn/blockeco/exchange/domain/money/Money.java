package cn.blockeco.exchange.domain.money;

import java.math.BigDecimal;

public record Money(long minorUnits) {

    public static Money ofMinor(long minorUnits) {
        return new Money(minorUnits);
    }

    public static Money zero() {
        return new Money(0);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minorUnits, other.minorUnits));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(minorUnits, other.minorUnits));
    }

    public BigDecimal toMajor(int scale) {
        return BigDecimal.valueOf(minorUnits, scale);
    }

    public static Money fromMajor(BigDecimal majorUnits, int scale) {
        return new Money(majorUnits.movePointRight(scale).longValueExact());
    }

    public Money requireNonNegative(String field) {
        if (minorUnits < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return this;
    }
}
