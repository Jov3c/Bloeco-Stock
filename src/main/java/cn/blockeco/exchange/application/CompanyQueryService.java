package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.ports.CompanyRepository;
import cn.blockeco.exchange.ports.RegistrationSagaRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Read-only SQL queries, always dispatched off Paper's main thread. */
public final class CompanyQueryService {
    private final CompanyRepository companies; private final RegistrationSagaRepository sagas; private final Executor sqlExecutor;
    public CompanyQueryService(CompanyRepository companies, RegistrationSagaRepository sagas, Executor sqlExecutor) { this.companies=companies; this.sagas=sagas; this.sqlExecutor=sqlExecutor; }
    public CompletionStage<Optional<Company>> findByName(String name) { return CompletableFuture.supplyAsync(() -> companies.findByNormalizedName(name.trim().toLowerCase(Locale.ROOT)), sqlExecutor); }
    public CompletionStage<List<RegistrationSaga>> recoveryList() { return CompletableFuture.supplyAsync(sagas::findRecoveryRecords, sqlExecutor); }
}
