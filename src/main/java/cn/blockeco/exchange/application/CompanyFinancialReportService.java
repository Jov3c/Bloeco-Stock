package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.ports.CompanyOperationsRepository;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Closes the prior calendar month in the configured server zone; scheduling is intentionally external. */
public final class CompanyFinancialReportService {
    private final CompanyOperationsRepository reports; private final TransactionRunner transactions; private final Executor executor; private final AppClock clock; private final ZoneId zone;
    public CompanyFinancialReportService(CompanyOperationsRepository reports, TransactionRunner transactions, Executor executor, AppClock clock, ZoneId zone) { this.reports=Objects.requireNonNull(reports);this.transactions=Objects.requireNonNull(transactions);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock);this.zone=Objects.requireNonNull(zone); }
    public CompletionStage<List<CompanyId>> closePreviousMonth() {
        Instant now=clock.now(); YearMonth month=YearMonth.from(now.atZone(zone)).minusMonths(1);
        return CompletableFuture.supplyAsync(()->{ List<CompanyId> created=new ArrayList<>(); for(CompanyId company:reports.listedCompanyIds()) if(transactions.inTransaction(c->reports.generateMonthlyReport(c,company,month,zone,now))) created.add(company); return List.copyOf(created); },executor);
    }
}
