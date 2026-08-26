package cn.blockeco.exchange.application;

import cn.blockeco.exchange.ports.BluechipRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Reads persisted daily candles plus the current server-time-zone session summary. */
public final class MarketChartQueryService {
    private static final LocalTime MARKET_OPENS = LocalTime.of(8, 0); private static final LocalTime MARKET_CLOSES = LocalTime.of(20, 0);
    private final BluechipRepository repository; private final Executor executor; private final Clock clock; private final ZoneId zone;
    public MarketChartQueryService(BluechipRepository repository, Executor executor, Clock clock, ZoneId zone) { this.repository=Objects.requireNonNull(repository);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);this.zone=Objects.requireNonNull(zone); }
    public CompletionStage<Optional<MarketChart>> chart(String stockCode) { return CompletableFuture.supplyAsync(() -> repository.findByStockCode(stockCode).map(company -> {
        LocalDate day=clock.instant().atZone(zone).toLocalDate(); Instant start=day.atTime(MARKET_OPENS).atZone(zone).toInstant(); Instant end=day.atTime(MARKET_CLOSES).atZone(zone).toInstant();
        var session=repository.sessionCandle(company.companyId(),start,end); List<MarketChart.DailyCandle> daily=repository.recentCandles(company.companyId(),5).stream().map(c -> new MarketChart.DailyCandle(c.day(),c.candle().open(),c.candle().high(),c.candle().low(),c.candle().close(),c.candle().volumeShares())).toList();
        return new MarketChart(day,new MarketChart.SessionSummary(session.open(),session.high(),session.low(),session.close(),session.volumeShares()),daily, bucket(repository.sessionTrades(company.companyId(), start, end), day));
    }),executor); }
    private List<MarketChart.IntradayPoint> bucket(List<cn.blockeco.exchange.ports.BluechipRepository.IntradayTrade> trades, LocalDate day) {
        var buckets = new java.util.LinkedHashMap<LocalDateTime, Bucket>();
        for (var trade : trades) { LocalDateTime local = trade.occurredAt().atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES); LocalDateTime key = local.withMinute((local.getMinute() / 30) * 30).withSecond(0).withNano(0); buckets.computeIfAbsent(key, ignored -> new Bucket()).add(trade); }
        return buckets.entrySet().stream().filter(entry -> entry.getKey().toLocalDate().equals(day)).map(entry -> new MarketChart.IntradayPoint(entry.getKey().toLocalTime().toString(), entry.getValue().close, entry.getValue().volume)).toList();
    }
    private static final class Bucket { private cn.blockeco.exchange.domain.money.Money close; private long volume; void add(cn.blockeco.exchange.ports.BluechipRepository.IntradayTrade trade) { close=trade.price(); volume=Math.addExact(volume,trade.shares()); } }
}
