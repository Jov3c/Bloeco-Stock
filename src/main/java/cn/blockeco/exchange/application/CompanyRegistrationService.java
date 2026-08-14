package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import cn.blockeco.exchange.ports.*;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

public final class CompanyRegistrationService {
    private static final Money CAPITAL = Money.ofMinor(1_000_000);
    private static final Money FEE = Money.ofMinor(100_000);
    private final CompanyRepository companies; private final RegistrationSagaRepository sagas; private final AuditLog audits;
    private final TransactionRunner transactions; private final EconomyGateway economy; private final MainThreadExecutor mainThread;
    private final Executor sqlExecutor; private final AppClock clock;
    public CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock) {
        this.companies=companies; this.sagas=sagas; this.audits=audits; this.transactions=transactions; this.economy=economy;
        this.mainThread=mainThread; this.sqlExecutor=sqlExecutor; this.clock=clock;
    }
    public CompletionStage<RegistrationResult> register(RegistrationRequest request) {
        return CompletableFuture.supplyAsync(() -> prepare(request), sqlExecutor).thenCompose(prepared -> {
            if (prepared.result != null) return CompletableFuture.completedFuture(prepared.result);
            return mainThread.submit(() -> economy.withdraw(request.founderId(), prepared.saga.totalWithdrawal()))
                    .thenCompose(outcome -> afterWithdrawal(request, prepared.saga, outcome));
        });
    }
    /** Marks only definitely stale PREPARED records; it never attempts a monetary action. */
    public CompletionStage<Integer> recoverStaleRegistrations(Instant cutoff) {
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (RegistrationSaga saga : sagas.findPreparedBefore(cutoff)) {
                transactions.inTransaction(connection -> { sagas.transition(connection, saga.id(), RegistrationSagaState.AMBIGUOUS, "crash window before withdrawal persistence"); return null; });
                count++;
            }
            for (RegistrationSaga saga : sagas.findWithdrawnBefore(cutoff)) {
                transactions.inTransaction(connection -> { sagas.transition(connection, saga.id(), RegistrationSagaState.REFUND_REQUIRED, "stale withdrawal requires compensation review"); return null; });
                count++;
            }
            return count;
        }, sqlExecutor);
    }
    private Prepared prepare(RegistrationRequest request) {
        DividendRate rate = DividendRate.fromPercent(request.dividendPercent());
        Company candidate = Company.register(new CompanyId(UUID.randomUUID()), request.companyName(), request.founderId(), CAPITAL, rate, clock.now());
        if (companies.findByNormalizedName(candidate.normalizedName()).isPresent()) return new Prepared(null, RegistrationResult.of(RegistrationResult.Status.DUPLICATE_NAME, "company name already exists"));
        RegistrationSaga saga = new RegistrationSaga(UUID.randomUUID(), request.founderId(), candidate.normalizedName(), FEE.plus(CAPITAL), RegistrationSagaState.PREPARED, null, clock.now(), clock.now());
        try {
            transactions.inTransaction(connection -> { sagas.save(connection, saga); audits.append(connection, event(saga, "COMPANY_REGISTRATION_PREPARED")); return null; });
            return new Prepared(saga, null);
        } catch (DuplicateCompanyNameException duplicate) {
            return new Prepared(null, RegistrationResult.of(RegistrationResult.Status.DUPLICATE_NAME, duplicate.getMessage()));
        }
    }
    private CompletionStage<RegistrationResult> afterWithdrawal(RegistrationRequest request, RegistrationSaga saga, EconomyGateway.Result outcome) {
        if (outcome.outcome() != EconomyGateway.Outcome.SUCCESS) {
            RegistrationResult.Status status = outcome.outcome() == EconomyGateway.Outcome.INSUFFICIENT_FUNDS
                    ? RegistrationResult.Status.INSUFFICIENT_FUNDS : RegistrationResult.Status.PROVIDER_FAILURE;
            return CompletableFuture.supplyAsync(() -> reject(saga, outcome.message(), status), sqlExecutor);
        }
        return CompletableFuture.supplyAsync(() -> persistCompleted(request, saga), sqlExecutor).handle((result, failure) -> failure == null ? CompletableFuture.completedFuture(result) : refund(saga, failure)).thenCompose(stage -> stage);
    }
    private RegistrationResult reject(RegistrationSaga saga, String message, RegistrationResult.Status status) {
        transactions.inTransaction(connection -> { sagas.transition(connection, saga.id(), RegistrationSagaState.REJECTED, message); audits.append(connection, event(saga, "COMPANY_REGISTRATION_REJECTED")); return null; });
        return RegistrationResult.of(status, message);
    }
    private RegistrationResult persistCompleted(RegistrationRequest request, RegistrationSaga saga) {
        Company company = Company.register(new CompanyId(UUID.randomUUID()), request.companyName(), request.founderId(), CAPITAL, DividendRate.fromPercent(request.dividendPercent()), clock.now());
        transactions.inTransaction(connection -> { sagas.transition(connection, saga.id(), RegistrationSagaState.WITHDRAWN, null); return null; });
        transactions.inTransaction(connection -> { companies.insert(connection, company); audits.append(connection, new AuditEvent(UUID.randomUUID(), Optional.of(company.id()), Optional.of(request.founderId()), "COMPANY_REGISTERED", Map.of("capitalMinor", CAPITAL.minorUnits()), clock.now())); sagas.transition(connection, saga.id(), RegistrationSagaState.COMPLETED, null); return null; });
        return RegistrationResult.of(RegistrationResult.Status.SUCCESS, "");
    }
    private CompletionStage<RegistrationResult> refund(RegistrationSaga saga, Throwable failure) {
        String sqlError = "SQL completion failure: " + failure.getMessage();
        return CompletableFuture.runAsync(() -> transactions.inTransaction(connection -> { sagas.transition(connection, saga.id(), RegistrationSagaState.REFUND_REQUIRED, sqlError); return null; }), sqlExecutor)
                .handle((ignored, updateFailure) -> null)
                .thenCompose(ignored -> mainThread.submit(() -> economy.deposit(saga.founderId(), saga.totalWithdrawal())))
                .thenApplyAsync(refund -> {
                    String diagnostic = sqlError + "; refund: " + refund.message();
                    if (refund.outcome() != EconomyGateway.Outcome.SUCCESS) {
                        try { transactions.inTransaction(connection -> { sagas.transition(connection, saga.id(), RegistrationSagaState.REFUND_REQUIRED, diagnostic); return null; }); } catch (RuntimeException ignored) { }
                        return RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, diagnostic);
                    }
                    try {
                        transactions.inTransaction(connection -> { sagas.transition(connection, saga.id(), RegistrationSagaState.REFUNDED, sqlError); return null; });
                        return RegistrationResult.of(RegistrationResult.Status.REFUNDED_AFTER_FAILURE, sqlError);
                    } catch (RuntimeException updateFailure) {
                        return RegistrationResult.of(RegistrationResult.Status.RECOVERY_REQUIRED, diagnostic + "; state update failed: " + updateFailure.getMessage());
                    }
                }, sqlExecutor);
    }
    private AuditEvent event(RegistrationSaga saga, String type) { return new AuditEvent(UUID.randomUUID(), Optional.empty(), Optional.of(saga.founderId()), type, Map.of("sagaId", saga.id().toString()), clock.now()); }
    private record Prepared(RegistrationSaga saga, RegistrationResult result) { }
}
