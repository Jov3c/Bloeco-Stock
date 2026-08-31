package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

/** Pure score from observable book pressure, model deviation and fictional active events. */
public final class QuantSignalPolicy {
    public Signal evaluate(SecondaryMarketQueryService.OrderBook book, Money lastPrice, Money modelPrice, int eventImpactBps) {
        Objects.requireNonNull(book); Objects.requireNonNull(lastPrice); Objects.requireNonNull(modelPrice);
        if (lastPrice.minorUnits() <= 0) throw new IllegalArgumentException("last price must be positive");
        long bids = book.bids().stream().mapToLong(OrderBookLevel::shares).sum();
        long asks = book.asks().stream().mapToLong(OrderBookLevel::shares).sum();
        if (bids + asks == 0) return new Signal("NONE", 0);
        long bookBps = Math.floorDiv(Math.multiplyExact(Math.subtractExact(bids, asks), 10_000L), Math.addExact(bids, asks));
        long modelBps = Math.floorDiv(Math.multiplyExact(Math.subtractExact(modelPrice.minorUnits(), lastPrice.minorUnits()), 10_000L), lastPrice.minorUnits());
        long score = Math.max(-10_000L, Math.min(10_000L, Math.addExact(Math.addExact(bookBps, modelBps), eventImpactBps)));
        if (score == 0) return new Signal("NONE", 0);
        return new Signal(score > 0 ? "BUY" : "SELL", Math.toIntExact(Math.abs(score)));
    }

    /**
     * The simulated exchange needs background flow even while the visible maker book is symmetric.
     * Each twenty strategy buckets deliberately admits thirteen projected microstructure signals,
     * producing the configured long-run 65% activity target without granting unlimited capital.
     */
    public Signal projectForMarketActivity(Signal observed, String stockCode, long step, int thresholdBps) {
        Objects.requireNonNull(observed); Objects.requireNonNull(stockCode);
        if (thresholdBps < 0 || thresholdBps > 10_000) throw new IllegalArgumentException("threshold must be between 0 and 10000");
        if (observed.confidenceBps() >= thresholdBps) return observed;
        if (Math.floorMod(step, 20L) >= 13L) return observed;
        int confidence = Math.min(10_000, thresholdBps + Math.floorMod(stockCode.hashCode() + step, 1_501));
        return new Signal(Math.floorMod(stockCode.hashCode() + step, 2L) == 0 ? "BUY" : "SELL", confidence);
    }

    public record Signal(String direction, int confidenceBps) {
        public Signal {
            if (!"BUY".equals(direction) && !"SELL".equals(direction) && !"NONE".equals(direction)) throw new IllegalArgumentException("unknown signal direction");
            if (confidenceBps < 0 || confidenceBps > 10_000) throw new IllegalArgumentException("confidence must be between 0 and 10000");
        }
    }
}
