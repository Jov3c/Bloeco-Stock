package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import cn.blockeco.exchange.domain.finance.CompanyCashAccount;
import cn.blockeco.exchange.domain.finance.ShareHolding;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.finance.TreasuryOperationState;
import cn.blockeco.exchange.ports.*;
import cn.blockeco.exchange.paper.CompanyCreationRules;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class CompanyRegistrationService {
    private final Money registrationFee; private final Supplier<Money> minimumCapital;
    private final CompanyRepository companies; private final RegistrationSagaRepository sagas; private final AuditLog audits;
    private final TransactionRunner transactions; private final EconomyGateway economy; private final MainThreadExecutor mainThread;
    private final Executor sqlExecutor; private final AppClock clock;
    private final CompanyCapitalizationService capitalization;
    private final CompanyFinanceRepository finance;
    private final TreasuryEscrowGateway escrow;
    private final long initialShares;
    public CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Money minimumCapital) {
        this(companies, sagas, audits, transactions, economy, mainThread, sqlExecutor, clock, registrationFee, minimumCapital, null);
    }
    /** Reads the minimum once as registration begins, before any Vault interaction. */
    public CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Supplier<CompanyCreationRules> liveRules, CompanyFinanceRepository finance, TreasuryEscrowGateway escrow, long initialShares) {
        this(companies, sagas, audits, transactions, economy, mainThread, sqlExecutor, clock, registrationFee, () -> liveRules.get().minimumCapital(), null, finance, escrow, initialShares);
    }
    public CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Money minimumCapital, CompanyCapitalizationService capitalization) {
        this(companies, sagas, audits, transactions, economy, mainThread, sqlExecutor, clock, registrationFee, minimumCapital, capitalization, null, null);
    }
    /** Production registration path: the capital was included in the one registration withdrawal. */
    public CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Money minimumCapital, CompanyFinanceRepository finance, TreasuryEscrowGateway escrow) {
        this(companies, sagas, audits, transactions, economy, mainThread, sqlExecutor, clock, registrationFee, minimumCapital, finance, escrow, 1_000);
    }
    public CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Money minimumCapital, CompanyFinanceRepository finance, TreasuryEscrowGateway escrow, long initialShares) {
        this(companies, sagas, audits, transactions, economy, mainThread, sqlExecutor, clock, registrationFee, minimumCapital, null, finance, escrow, initialShares);
    }
    private CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Money minimumCapital, CompanyCapitalizationService capitalization, CompanyFinanceRepository finance, TreasuryEscrowGateway escrow) {
        this(companies, sagas, audits, transactions, economy, mainThread, sqlExecutor, clock, registrationFee, minimumCapital, capitalization, finance, escrow, 1_000);
    }
    private CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Money minimumCapital, CompanyCapitalizationService capitalization, CompanyFinanceRepository finance, TreasuryEscrowGateway escrow, long initialShares) {
        this(companies, sagas, audits, transactions, economy, mainThread, sqlExecutor, clock, registrationFee, () -> minimumCapital, capitalization, finance, escrow, initialShares);
    }
    private CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Supplier<Money> minimumCapital, CompanyCapitalizationService capitalization, CompanyFinanceRepository finance, TreasuryEscrowGateway escrow, long initialShares) {
        if (initialShares < 1_000) throw new IllegalArgumentException("initialShares must be at least 1000");
        this.companies=companies; this.sagas=sagas; this.audits=audits; this.transactions=transactions; this.economy=economy;
        this.mainThread=mainThread; this.sqlExecutor=sqlExecutor; this.clock=clock; this.registrationFee=registrationFee; this.minimumCapital=minimumCapital; this.capitalization=capitalization; this.finance=finance; this.escrow=escrow; this.initialShares=initialShares;
    }
    public CompletionStage<RegistrationResult> register(RegistrationRequest request) {
        Money minimum = minimumCapital.get();
        Money paidInCapital = paidInCapital(request, minimum);
        if (paidInCapital.minorUnits() <= 0 || paidInCapital.minorUnits() < minimum.minorUnits())
            return CompletableFuture.completedFuture(RegistrationResult.of(RegistrationResult.Status.PROVIDER_FAILURE, "paid-in capital is below the configured minimum"));
        RegistrationRequest snapshot = request.paidInCapital().minorUnits() == 0 ? new RegistrationRequest(request.founderId(), request.companyName(), paidInCapital, request.dividendPercent()) : request;
        return CompletableFuture.supplyAsync(() -> prepare(snapshot), sqlExecutor).thenCompose(prepared -> {
            if (prepared.result != null) return CompletableFuture.completedFuture(prepared.result);
            return mainThread.submit(() -> economy.withdraw(snapshot.founderId(), prepared.saga.totalWithdrawal()))
                    .thenCompose(outcome -> afterWithdrawal(snapshot, prepared.saga, outcome));
        });
    }
    /** Marks only definitely stale PREPARED records; it never attempts a monetary action. */
    public CompletionStage<Integer> recoverStaleRegistrations(Instant cutoff) {
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (RegistrationSaga saga : sagas.findPreparedBefore(cutoff)) {
                transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.PREPARED, RegistrationSagaState.AMBIGUOUS, "crash window before withdrawal persistence"); return null; });
                count++;
            }
            for (RegistrationSaga saga : sagas.findEscrowWithdrawnBefore(cutoff)) {
                transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.WITHDRAWN, RegistrationSagaState.AMBIGUOUS, "crash window during escrow deposit; Vault outcome is unknown"); return null; });
                count++;
            }
            for (RegistrationSaga saga : sagas.findWithdrawnBefore(cutoff)) {
                transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.WITHDRAWN, RegistrationSagaState.REFUND_REQUIRED, "stale withdrawal requires compensation review"); return null; });
                count++;
            }
            return count;
        }, sqlExecutor);
    }
    private Prepared prepare(RegistrationRequest request) {
        DividendRate rate = DividendRate.fromPercent(request.dividendPercent());
        Money paidInCapital = paidInCapital(request);
        Company candidate = Company.register(new CompanyId(UUID.randomUUID()), request.companyName(), request.founderId(), paidInCapital, initialShares, rate, clock.now());
        if (companies.findByNormalizedName(candidate.normalizedName()).isPresent()) return new Prepared(null, RegistrationResult.of(RegistrationResult.Status.DUPLICATE_NAME, "company name already exists"));
        RegistrationSaga saga = new RegistrationSaga(UUID.randomUUID(), request.founderId(), candidate.normalizedName(), registrationFee.plus(paidInCapital), RegistrationSagaState.PREPARED, null, clock.now(), clock.now(), finance != null);
        try {
            transactions.inTransaction(connection -> { sagas.save(connection, saga); audits.append(connection, event(saga, "NONE", RegistrationSagaState.PREPARED, null)); return null; });
            return new Prepared(saga, null);
        } catch (DuplicateCompanyNameException duplicate) {
            return new Prepared(null, RegistrationResult.of(RegistrationResult.Status.DUPLICATE_NAME, duplicate.getMessage()));
        }
    }
    private CompletionStage<RegistrationResult> afterWithdrawal(RegistrationRequest request, RegistrationSaga saga, EconomyGateway.Result outcome) {
        if (outcome.outcome() != EconomyGateway.Outcome.SUCCESS) {
            if (finance != null && outcome.outcome() == EconomyGateway.Outcome.PROVIDER_FAILURE) return CompletableFuture.supplyAsync(() -> ambiguous(saga, RegistrationSagaState.PREPARED, outcome.message()), sqlExecutor);
            RegistrationResult.Status status = outcome.outcome() == EconomyGateway.Outcome.INSUFFICIENT_FUNDS
                    ? RegistrationResult.Status.INSUFFICIENT_FUNDS : RegistrationResult.Status.PROVIDER_FAILURE;
            return CompletableFuture.supplyAsync(() -> reject(saga, outcome.message(), status), sqlExecutor);
        }
        if (finance != null) return afterCollectedWithdrawal(request, saga);
        return CompletableFuture.supplyAsync(() -> persistCompleted(request, saga), sqlExecutor).handle((result, failure) -> failure == null ? CompletableFuture.completedFuture(result) : refund(saga, failure)).thenCompose(stage -> stage);
    }
    private CompletionStage<RegistrationResult> afterCollectedWithdrawal(RegistrationRequest request, RegistrationSaga saga) {
        return CompletableFuture.runAsync(() -> transitionAndAudit(saga, RegistrationSagaState.PREPARED, RegistrationSagaState.WITHDRAWN, "confirmed combined registration withdrawal"), sqlExecutor)
                .thenCompose(ignored -> CompletableFuture.supplyAsync(() -> escrow.depositEscrow(request.paidInCapital(), saga.id())))
                .thenCompose(result -> {
                    if (result.outcome() != EconomyGateway.Outcome.SUCCESS) return CompletableFuture.supplyAsync(() -> ambiguous(saga, RegistrationSagaState.WITHDRAWN, result.message()), sqlExecutor);
                    return CompletableFuture.supplyAsync(() -> completeEscrowedRegistration(request, saga), sqlExecutor);
                })
                .exceptionally(failure -> RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, "registration requires administrator recovery: " + failure.getMessage()));
    }
    private RegistrationResult completeEscrowedRegistration(RegistrationRequest request, RegistrationSaga saga) {
        Money capital = request.paidInCapital();
        Company company = Company.register(new CompanyId(UUID.randomUUID()), request.companyName(), request.founderId(), capital, initialShares, DividendRate.fromPercent(request.dividendPercent()), clock.now());
        try {
            transactions.inTransaction(c -> {
                transitionAndAudit(c, saga, RegistrationSagaState.WITHDRAWN, RegistrationSagaState.ESCROW_DEPOSITED, "confirmed escrow deposit");
                companies.insert(c, company);
                TreasuryOperation operation = new TreasuryOperation(saga.id(), company.id(), request.founderId(), capital, saga.id().toString(), TreasuryOperationState.PREPARED, clock.now(), clock.now());
                finance.prepare(c, operation, capitalizationEvent(operation, "NONE", TreasuryOperationState.PREPARED, "registration capital already collected"));
                finance.transition(c, operation.id(), TreasuryOperationState.PREPARED, TreasuryOperationState.PLAYER_WITHDRAWN, capitalizationEvent(operation, "PREPARED", TreasuryOperationState.PLAYER_WITHDRAWN, "combined withdrawal"));
                finance.transition(c, operation.id(), TreasuryOperationState.PLAYER_WITHDRAWN, TreasuryOperationState.ESCROW_DEPOSITED, capitalizationEvent(operation, "PLAYER_WITHDRAWN", TreasuryOperationState.ESCROW_DEPOSITED, "confirmed escrow deposit"));
                finance.createCapitalization(c, new CompanyCashAccount(company.id(), capital, capital, Money.zero(), Money.zero()), new ShareHolding(company.id(), request.founderId(), initialShares, 0), operation, capitalizationEvent(operation, "ESCROW_DEPOSITED", TreasuryOperationState.COMPLETED, ""));
                audits.append(c, new AuditEvent(UUID.randomUUID(), Optional.of(company.id()), Optional.of(request.founderId()), "COMPANY_REGISTERED", Map.of("capitalMinor", capital.minorUnits()), clock.now()));
                transitionAndAudit(c, saga, RegistrationSagaState.ESCROW_DEPOSITED, RegistrationSagaState.COMPLETED, null);
                return null;
            });
            return RegistrationResult.of(RegistrationResult.Status.SUCCESS, "");
        } catch (RuntimeException failure) { return RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, "escrow confirmed; database completion requires administrator recovery: " + failure.getMessage()); }
    }
    private RegistrationResult ambiguous(RegistrationSaga saga, RegistrationSagaState state, String reason) {
        transitionAndAudit(saga, state, RegistrationSagaState.AMBIGUOUS, reason);
        return RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, reason);
    }
    private void transitionAndAudit(RegistrationSaga saga, RegistrationSagaState from, RegistrationSagaState to, String reason) { transactions.inTransaction(c -> { transitionAndAudit(c, saga, from, to, reason); return null; }); }
    private AuditEvent capitalizationEvent(TreasuryOperation operation, String from, TreasuryOperationState to, String reason) { return new AuditEvent(UUID.randomUUID(), Optional.of(operation.companyId()), Optional.of(operation.playerId()), "COMPANY_CAPITALIZATION_" + to.name(), Map.of("operationId", operation.id().toString(), "fromState", from, "toState", to.name(), "amountMinor", operation.amount().minorUnits(), "reason", reason == null ? "" : reason), clock.now()); }
    private RegistrationResult reject(RegistrationSaga saga, String message, RegistrationResult.Status status) {
        transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.PREPARED, RegistrationSagaState.REJECTED, message); return null; });
        return RegistrationResult.of(status, message);
    }
    private RegistrationResult persistCompleted(RegistrationRequest request, RegistrationSaga saga) {
        Money paidInCapital = paidInCapital(request);
        Company company = Company.register(new CompanyId(UUID.randomUUID()), request.companyName(), request.founderId(), paidInCapital, initialShares, DividendRate.fromPercent(request.dividendPercent()), clock.now());
        transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.PREPARED, RegistrationSagaState.WITHDRAWN, null); return null; });
        transactions.inTransaction(connection -> { companies.insert(connection, company); audits.append(connection, new AuditEvent(UUID.randomUUID(), Optional.of(company.id()), Optional.of(request.founderId()), "COMPANY_REGISTERED", Map.of("capitalMinor", paidInCapital.minorUnits()), clock.now())); return null; });
        if (capitalization != null) capitalization.capitalize(company, request.founderId(), paidInCapital, saga.id()).toCompletableFuture().join();
        transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.WITHDRAWN, RegistrationSagaState.COMPLETED, null); return null; });
        return RegistrationResult.of(RegistrationResult.Status.SUCCESS, "");
    }
    private CompletionStage<RegistrationResult> refund(RegistrationSaga saga, Throwable failure) {
        String sqlError = "SQL completion failure: " + failure.getMessage();
        return CompletableFuture.runAsync(() -> transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.WITHDRAWN, RegistrationSagaState.REFUND_REQUIRED, sqlError); return null; }), sqlExecutor)
                .handle((ignored, updateFailure) -> updateFailure == null
                        ? CompletableFuture.<RegistrationResult>completedFuture(null)
                        : CompletableFuture.completedFuture(RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, sqlError + "; refund not attempted: " + updateFailure.getMessage())))
                .thenCompose(stage -> stage)
                .thenCompose(persistenceResult -> persistenceResult == null
                        ? mainThread.submit(() -> economy.deposit(saga.founderId(), saga.totalWithdrawal())).thenApply(refund -> refundResult(saga, sqlError, refund))
                        : CompletableFuture.completedFuture(persistenceResult))
                ;
    }
    private RegistrationResult refundResult(RegistrationSaga saga, String sqlError, EconomyGateway.Result refund) {
                    String diagnostic = sqlError + "; refund: " + refund.message();
                    if (refund.outcome() != EconomyGateway.Outcome.SUCCESS) {
                        try { transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.REFUND_REQUIRED, RegistrationSagaState.REFUND_REQUIRED, diagnostic); return null; }); } catch (RuntimeException ignored) { }
                        return RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, diagnostic);
                    }
                    try {
                        transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.REFUND_REQUIRED, RegistrationSagaState.REFUNDED, sqlError); return null; });
                        return RegistrationResult.of(RegistrationResult.Status.REFUNDED_AFTER_FAILURE, sqlError);
                    } catch (RuntimeException updateFailure) {
                        return RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, diagnostic + "; state update failed: " + updateFailure.getMessage());
                    }
    }
    private void transitionAndAudit(java.sql.Connection connection, RegistrationSaga saga, RegistrationSagaState from, RegistrationSagaState to, String diagnostic) throws java.sql.SQLException {
        sagas.transition(connection, saga.id(), from, to, diagnostic);
        audits.append(connection, event(saga, from, to, diagnostic));
    }
    private AuditEvent event(RegistrationSaga saga, RegistrationSagaState from, RegistrationSagaState to, String diagnostic) {
        return event(saga, from.name(), to, diagnostic);
    }
    private AuditEvent event(RegistrationSaga saga, String from, RegistrationSagaState to, String diagnostic) {
        return new AuditEvent(UUID.randomUUID(), Optional.empty(), Optional.of(saga.founderId()), "COMPANY_REGISTRATION_" + to.name(), Map.of(
                "sagaId", saga.id().toString(), "fromState", from, "toState", to.name(),
                "totalWithdrawalMinor", saga.totalWithdrawal().minorUnits(), "reason", diagnostic == null ? "" : diagnostic), clock.now());
    }
    private Money paidInCapital(RegistrationRequest request) { return paidInCapital(request, minimumCapital.get()); }
    private Money paidInCapital(RegistrationRequest request, Money minimum) {
        if (request.paidInCapital().minorUnits() == 0 && finance != null) return request.paidInCapital();
        return request.paidInCapital().minorUnits() == 0 ? minimum : request.paidInCapital();
    }
    private record Prepared(RegistrationSaga saga, RegistrationResult result) { }
}
