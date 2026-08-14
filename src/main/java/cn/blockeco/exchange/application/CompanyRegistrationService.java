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
    private final Money registrationFee; private final Money minimumCapital;
    private final CompanyRepository companies; private final RegistrationSagaRepository sagas; private final AuditLog audits;
    private final TransactionRunner transactions; private final EconomyGateway economy; private final MainThreadExecutor mainThread;
    private final Executor sqlExecutor; private final AppClock clock;
    public CompanyRegistrationService(CompanyRepository companies, RegistrationSagaRepository sagas, AuditLog audits,
            TransactionRunner transactions, EconomyGateway economy, MainThreadExecutor mainThread, Executor sqlExecutor, AppClock clock, Money registrationFee, Money minimumCapital) {
        this.companies=companies; this.sagas=sagas; this.audits=audits; this.transactions=transactions; this.economy=economy;
        this.mainThread=mainThread; this.sqlExecutor=sqlExecutor; this.clock=clock; this.registrationFee=registrationFee; this.minimumCapital=minimumCapital;
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
                transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.PREPARED, RegistrationSagaState.AMBIGUOUS, "crash window before withdrawal persistence"); return null; });
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
        Company candidate = Company.register(new CompanyId(UUID.randomUUID()), request.companyName(), request.founderId(), minimumCapital, rate, clock.now());
        if (companies.findByNormalizedName(candidate.normalizedName()).isPresent()) return new Prepared(null, RegistrationResult.of(RegistrationResult.Status.DUPLICATE_NAME, "company name already exists"));
        RegistrationSaga saga = new RegistrationSaga(UUID.randomUUID(), request.founderId(), candidate.normalizedName(), registrationFee.plus(minimumCapital), RegistrationSagaState.PREPARED, null, clock.now(), clock.now());
        try {
            transactions.inTransaction(connection -> { sagas.save(connection, saga); audits.append(connection, event(saga, "NONE", RegistrationSagaState.PREPARED, null)); return null; });
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
        transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.PREPARED, RegistrationSagaState.REJECTED, message); return null; });
        return RegistrationResult.of(status, message);
    }
    private RegistrationResult persistCompleted(RegistrationRequest request, RegistrationSaga saga) {
        Company company = Company.register(new CompanyId(UUID.randomUUID()), request.companyName(), request.founderId(), minimumCapital, DividendRate.fromPercent(request.dividendPercent()), clock.now());
        transactions.inTransaction(connection -> { transitionAndAudit(connection, saga, RegistrationSagaState.PREPARED, RegistrationSagaState.WITHDRAWN, null); return null; });
        transactions.inTransaction(connection -> { companies.insert(connection, company); audits.append(connection, new AuditEvent(UUID.randomUUID(), Optional.of(company.id()), Optional.of(request.founderId()), "COMPANY_REGISTERED", Map.of("capitalMinor", minimumCapital.minorUnits()), clock.now())); transitionAndAudit(connection, saga, RegistrationSagaState.WITHDRAWN, RegistrationSagaState.COMPLETED, null); return null; });
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
    private record Prepared(RegistrationSaga saga, RegistrationResult result) { }
}
