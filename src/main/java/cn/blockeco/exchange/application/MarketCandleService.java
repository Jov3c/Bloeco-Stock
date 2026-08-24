package cn.blockeco.exchange.application;

import cn.blockeco.exchange.ports.BluechipRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Idempotently records a daily OHLCV snapshot for every system listing. */
public final class MarketCandleService {
    private final BluechipRepository repository; private final TransactionRunner transactions; private final Executor executor;
    public MarketCandleService(BluechipRepository repository,TransactionRunner transactions,Executor executor){this.repository=Objects.requireNonNull(repository);this.transactions=Objects.requireNonNull(transactions);this.executor=Objects.requireNonNull(executor);}
    public CompletionStage<Void> closeTradingDay(LocalDate day){Objects.requireNonNull(day);return CompletableFuture.runAsync(()->{ var companies=repository.all(); for(var bluechip:companies) transactions.inTransaction(c->{repository.closeCandle(c,bluechip.companyId(),day);return null;}); },executor);}
}
