package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.BluechipRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Settles the persisted fifteen-day cycle without ever crossing an external money boundary. */
public final class DividendCycleService {
    private final BluechipRepository repository;
    private final TransactionRunner transactions;
    private final Executor executor;
    private final AppClock clock;
    private final long bluechipBaseProfitMinor;

    public DividendCycleService(BluechipRepository repository, TransactionRunner transactions, Executor executor,
                                AppClock clock, long bluechipBaseProfitMinor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bluechipBaseProfitMinor = bluechipBaseProfitMinor;
    }

    public CompletionStage<List<DividendRunResult>> settleDueRuns() {
        return CompletableFuture.supplyAsync(() -> {
            List<DividendRunResult> settled = transactions.inTransaction(connection -> repository
                    .settleDueDividendRuns(connection, clock.now(), bluechipBaseProfitMinor).stream()
                    .map(run -> new DividendRunResult(run.companyId(), run.profit(), run.distributed(), run.paymentCount(), run.idempotencyKey()))
                    .toList());
            return settled;
        }, executor);
    }

    public record DividendRunResult(CompanyId companyId, Money profit, Money distributed, int paymentCount,
                                    String idempotencyKey) { }
}
