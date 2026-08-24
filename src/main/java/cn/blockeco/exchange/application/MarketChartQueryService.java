package cn.blockeco.exchange.application;

import cn.blockeco.exchange.ports.BluechipRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Reads persisted daily candles plus the current server-time-zone session summary. */
public final class MarketChartQueryService {
    private final BluechipRepository repository; private final Executor executor; private final Clock clock; private final ZoneId zone;
    public MarketChartQueryService(BluechipRepository repository, Executor executor, Clock clock, ZoneId zone) { this.repository=Objects.requireNonNull(repository);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);this.zone=Objects.requireNonNull(zone); }
    public CompletionStage<Optional<MarketChart>> chart(String stockCode) { return CompletableFuture.supplyAsync(() -> repository.findByStockCode(stockCode).map(company -> {
        LocalDate day=clock.instant().atZone(zone).toLocalDate(); Instant start=day.atStartOfDay(zone).toInstant(); Instant end=day.plusDays(1).atStartOfDay(zone).toInstant();
        var session=repository.sessionCandle(company.companyId(),start,end); List<MarketChart.DailyCandle> daily=repository.recentCandles(company.companyId(),5).stream().map(c -> new MarketChart.DailyCandle(c.day(),c.candle().open(),c.candle().high(),c.candle().low(),c.candle().close(),c.candle().volumeShares())).toList();
        return new MarketChart(day,new MarketChart.SessionSummary(session.open(),session.high(),session.low(),session.close(),session.volumeShares()),daily);
    }),executor); }
}
