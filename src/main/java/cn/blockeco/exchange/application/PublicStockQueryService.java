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

/** Fully asynchronous public read model. */
public final class PublicStockQueryService {
    private final PublicStockRepository repository; private final Executor sqlExecutor;
    public PublicStockQueryService(PublicStockRepository repository, Executor sqlExecutor) { this.repository=Objects.requireNonNull(repository); this.sqlExecutor=Objects.requireNonNull(sqlExecutor); }
    public CompletionStage<List<PublicMarketRow>> market() { return CompletableFuture.supplyAsync(repository::market, sqlExecutor); }
    public CompletionStage<List<PublicOfferingView>> ipo(int limit) { return CompletableFuture.supplyAsync(()->repository.listOfferings(limit), sqlExecutor); }
    public CompletionStage<Optional<PublicStockInfo>> info(String companyNameOrCode) { return CompletableFuture.supplyAsync(()->repository.findInfo(companyNameOrCode), sqlExecutor); }
    public CompletionStage<List<PublicAnnouncement>> announcements(String companyNameOrCode, int limit) { return CompletableFuture.supplyAsync(()->repository.findAnnouncements(companyNameOrCode,limit), sqlExecutor); }
    public CompletionStage<Optional<UUID>> resolveOpenOffering(String companyNameOrCode) { return CompletableFuture.supplyAsync(()->repository.findOpenOfferingByCompanyOrCode(companyNameOrCode), sqlExecutor); }
    public CompletionStage<List<PublicStockSymbol>> symbols() { return CompletableFuture.supplyAsync(repository::symbols, sqlExecutor); }
}
