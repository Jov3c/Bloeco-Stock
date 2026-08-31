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

    public record Signal(String direction, int confidenceBps) {
        public Signal {
            if (!"BUY".equals(direction) && !"SELL".equals(direction) && !"NONE".equals(direction)) throw new IllegalArgumentException("unknown signal direction");
            if (confidenceBps < 0 || confidenceBps > 10_000) throw new IllegalArgumentException("confidence must be between 0 and 10000");
        }
    }
}
