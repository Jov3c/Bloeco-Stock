package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.money.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuantSignalPolicyTest {
    private final QuantSignalPolicy policy = new QuantSignalPolicy();

    @Test
    void givesStrongBuyConfidenceToAOneSidedBookThatSupportsTheEventAdjustedModel() {
        var book = new SecondaryMarketQueryService.OrderBook(
                List.of(new OrderBookLevel(Money.ofMinor(1_000), 900)),
                List.of(new OrderBookLevel(Money.ofMinor(1_010), 100)));

        QuantSignalPolicy.Signal signal = policy.evaluate(book, Money.ofMinor(1_000), Money.ofMinor(970), 120);

        assertThat(signal).isEqualTo(new QuantSignalPolicy.Signal("BUY", 7_820));
    }

    @Test
    void givesNoTradeConfidenceToABalancedBookAtModelValueWithoutAnEvent() {
        var book = new SecondaryMarketQueryService.OrderBook(
                List.of(new OrderBookLevel(Money.ofMinor(1_000), 100)),
                List.of(new OrderBookLevel(Money.ofMinor(1_010), 100)));

        QuantSignalPolicy.Signal signal = policy.evaluate(book, Money.ofMinor(1_000), Money.ofMinor(1_000), 0);

        assertThat(signal).isEqualTo(new QuantSignalPolicy.Signal("NONE", 0));
    }

    @Test
    void simulatedMicrostructureProjectionActivatesExactlyThirteenOfTwentyWeakSignalBuckets() {
        long active = java.util.stream.LongStream.range(0, 20)
                .map(step -> policy.projectForMarketActivity(new QuantSignalPolicy.Signal("NONE", 0), "NOVA", step, 6_500).confidenceBps() >= 6_500 ? 1 : 0)
                .sum();

        assertThat(active).isEqualTo(13);
    }

    @Test
    void simulatedDirectionAlternatesWhenTheSameSymbolReturnsToTheOneSecondRotation() {
        var first = policy.projectForMarketActivity(new QuantSignalPolicy.Signal("NONE", 0), "NOVA", 0, 6_500);
        var nextRotation = policy.projectForMarketActivity(new QuantSignalPolicy.Signal("NONE", 0), "NOVA", 10, 6_500);

        assertThat(nextRotation.direction()).isNotEqualTo(first.direction());
    }
}
