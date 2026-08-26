package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Small no-indicator chart model suitable for a vanilla inventory tooltip. */
public record MarketChart(LocalDate sessionDay, SessionSummary sessionSummary, List<DailyCandle> dailyCandles, List<IntradayPoint> intradayPoints) {
    public MarketChart { Objects.requireNonNull(sessionDay); Objects.requireNonNull(sessionSummary); dailyCandles = List.copyOf(dailyCandles); intradayPoints = List.copyOf(intradayPoints); }
    public MarketChart(LocalDate sessionDay, SessionSummary sessionSummary, List<DailyCandle> dailyCandles) { this(sessionDay, sessionSummary, dailyCandles, List.of()); }
    public record SessionSummary(Money open, Money high, Money low, Money close, long volumeShares) { }
    public record DailyCandle(LocalDate day, Money open, Money high, Money low, Money close, long volumeShares) { }
    /** Closing price and aggregate shares for one 30-minute market-time bucket. */
    public record IntradayPoint(String label, Money close, long volumeShares) { public IntradayPoint { Objects.requireNonNull(label); Objects.requireNonNull(close); } }
}
