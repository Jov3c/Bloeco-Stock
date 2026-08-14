package cn.blockeco.exchange.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void converts_major_units_without_binary_rounding() {
        assertThat(Money.fromMajor(new BigDecimal("10.25"), 2).minorUnits()).isEqualTo(1025L);
    }

    @Test
    void converts_minor_units_to_major_units_at_the_requested_scale() {
        assertThat(Money.ofMinor(1025).toMajor(2)).isEqualByComparingTo("10.25");
    }

    @Test
    void creates_zero_money() {
        assertThat(Money.zero().minorUnits()).isZero();
    }

    @Test
    void adds_minor_units_exactly() {
        assertThat(Money.ofMinor(700).plus(Money.ofMinor(325)).minorUnits()).isEqualTo(1025L);
    }

    @Test
    void subtracts_minor_units_exactly() {
        assertThat(Money.ofMinor(700).minus(Money.ofMinor(1025)).minorUnits()).isEqualTo(-325L);
    }

    @Test
    void rejects_more_fraction_digits_than_currency_scale() {
        assertThatThrownBy(() -> Money.fromMajor(new BigDecimal("1.001"), 2))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void detects_addition_overflow() {
        assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE).plus(Money.ofMinor(1)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejects_negative_values_when_a_debit_requires_a_non_negative_amount() {
        assertThatThrownBy(() -> Money.ofMinor(-1).requireNonNegative("debit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("debit must not be negative");
    }
}
