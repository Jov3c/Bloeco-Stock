package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import cn.blockeco.exchange.ports.PublicStockRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Fully asynchronous public read model. */
public final class PublicStockQueryService {
    private final PublicStockRepository repository; private final Executor sqlExecutor; private final Clock clock; private final ZoneId zone;
    public PublicStockQueryService(PublicStockRepository repository, Executor sqlExecutor) { this(repository,sqlExecutor,Clock.systemUTC(),ZoneId.of("UTC")); }
    public PublicStockQueryService(PublicStockRepository repository, Executor sqlExecutor, Clock clock, ZoneId zone) { this.repository=Objects.requireNonNull(repository); this.sqlExecutor=Objects.requireNonNull(sqlExecutor);this.clock=Objects.requireNonNull(clock);this.zone=Objects.requireNonNull(zone); }
    public CompletionStage<List<PublicMarketRow>> market() { ZonedDateTime start=clock.instant().atZone(zone).toLocalDate().atStartOfDay(zone); return CompletableFuture.supplyAsync(()->repository.market(start.toInstant(),start.plusDays(1).toInstant()), sqlExecutor); }
    public CompletionStage<List<PublicOfferingView>> ipo(int limit) { return CompletableFuture.supplyAsync(()->repository.listOfferings(limit), sqlExecutor); }
    public CompletionStage<Optional<PublicStockInfo>> info(String companyNameOrCode) { return CompletableFuture.supplyAsync(()->repository.findInfo(companyNameOrCode), sqlExecutor); }
    public CompletionStage<List<PublicAnnouncement>> announcements(String companyNameOrCode, int limit) { return CompletableFuture.supplyAsync(()->repository.findAnnouncements(companyNameOrCode,limit), sqlExecutor); }
    public CompletionStage<Optional<UUID>> resolveOpenOffering(String companyNameOrCode) { return CompletableFuture.supplyAsync(()->repository.findOpenOfferingByCompanyOrCode(companyNameOrCode), sqlExecutor); }
    public CompletionStage<List<PublicStockSymbol>> symbols() { return CompletableFuture.supplyAsync(repository::symbols, sqlExecutor); }
    public CompletionStage<List<MarketNewsItem>> recentNews(int limit) { return CompletableFuture.supplyAsync(()->repository.recentNews(limit), sqlExecutor); }
}
