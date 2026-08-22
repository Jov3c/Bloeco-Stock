package cn.blockeco.exchange.application;

import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import cn.blockeco.exchange.ports.PublicStockRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** SQL-executor-only read projections for the secondary market. */
public final class SecondaryMarketQueryService {
    private final SecondaryTradingRepository repository; private final PublicStockRepository publicStocks; private final Executor sqlExecutor; private final Clock clock; private final ZoneId zone;
    public SecondaryMarketQueryService(SecondaryTradingRepository repository, PublicStockRepository publicStocks, Executor sqlExecutor, Clock clock, ZoneId zone) { this.repository=Objects.requireNonNull(repository);this.publicStocks=Objects.requireNonNull(publicStocks); this.sqlExecutor=Objects.requireNonNull(sqlExecutor); this.clock=Objects.requireNonNull(clock); this.zone=Objects.requireNonNull(zone); }
    public CompletionStage<PortfolioView> portfolio(UUID player) { return CompletableFuture.supplyAsync(()->repository.portfolio(player),sqlExecutor); }
    public CompletionStage<List<OrderView>> orders(UUID player,int limit) { return CompletableFuture.supplyAsync(()->repository.orders(player,boundHistory(limit)),sqlExecutor); }
    public CompletionStage<List<TradeView>> trades(UUID player,int limit) { return CompletableFuture.supplyAsync(()->repository.trades(player,boundHistory(limit)),sqlExecutor); }
    public CompletionStage<OrderBook> book(String code,int depth) { int bounded=Math.max(1,Math.min(5,depth)); return CompletableFuture.supplyAsync(()->new OrderBook(repository.bids(code,bounded),repository.asks(code,bounded)),sqlExecutor); }
    public CompletionStage<List<PublicMarketRow>> market() { var start=clock.instant().atZone(zone).toLocalDate().atStartOfDay(zone);return CompletableFuture.supplyAsync(()->publicStocks.market(start.toInstant(),start.plusDays(1).toInstant()),sqlExecutor); }
    private static int boundHistory(int limit){return Math.max(1,Math.min(50,limit));}
    public record OrderBook(List<OrderBookLevel> bids,List<OrderBookLevel> asks) { public OrderBook { bids=List.copyOf(bids); asks=List.copyOf(asks); } }
}
