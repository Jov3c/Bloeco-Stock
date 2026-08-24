package cn.blockeco.exchange.application;

import cn.blockeco.exchange.ports.BluechipRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Idempotently records a daily OHLCV snapshot for every system listing. */
public final class MarketCandleService {
    private final BluechipRepository repository; private final TransactionRunner transactions; private final Executor executor; private final ZoneId zone;
    public MarketCandleService(BluechipRepository repository,TransactionRunner transactions,Executor executor){this(repository,transactions,executor,ZoneId.of("UTC"));}
    public MarketCandleService(BluechipRepository repository,TransactionRunner transactions,Executor executor,ZoneId zone){this.repository=Objects.requireNonNull(repository);this.transactions=Objects.requireNonNull(transactions);this.executor=Objects.requireNonNull(executor);this.zone=Objects.requireNonNull(zone);}
    public CompletionStage<Void> closeTradingDay(LocalDate day){Objects.requireNonNull(day);return CompletableFuture.runAsync(()->{ var companies=repository.all(); for(var bluechip:companies) transactions.inTransaction(c->{repository.closeCandle(c,bluechip.companyId(),day,zone);return null;}); },executor);}
}
