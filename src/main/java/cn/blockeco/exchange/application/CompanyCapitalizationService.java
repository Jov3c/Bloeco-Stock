package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.finance.CompanyCashAccount;
import cn.blockeco.exchange.domain.finance.ShareHolding;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.finance.TreasuryOperationState;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.AuditLog;
import cn.blockeco.exchange.ports.CompanyFinanceRepository;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.TransactionRunner;
import cn.blockeco.exchange.ports.TreasuryEscrowGateway;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Coordinates the SQLite record with an external escrow transfer. */
public final class CompanyCapitalizationService {
    private final CompanyFinanceRepository finance; private final AuditLog audits; private final TransactionRunner transactions;
    private final TreasuryEscrowGateway escrow; private final MainThreadExecutor mainThread; private final Executor sql; private final AppClock clock;
    public CompanyCapitalizationService(CompanyFinanceRepository finance, AuditLog audits, TransactionRunner transactions, TreasuryEscrowGateway escrow, MainThreadExecutor mainThread, Executor sql, AppClock clock) {
        this.finance = finance; this.audits = audits; this.transactions = transactions; this.escrow = escrow; this.mainThread = mainThread; this.sql = sql; this.clock = clock;
    }
    public CompletionStage<Void> capitalize(Company company, UUID founder, Money capital, UUID registrationSagaId) {
        UUID id = registrationSagaId;
        return CompletableFuture.supplyAsync(() -> prepare(company, founder, capital, id), sql).thenCompose(existing -> {
            if (existing.state() == TreasuryOperationState.COMPLETED) return CompletableFuture.completedFuture(null);
            if (existing.state() != TreasuryOperationState.PREPARED) return CompletableFuture.failedFuture(new IllegalStateException("capitalization requires recovery: " + existing.state()));
            return CompletableFuture.supplyAsync(() -> escrow.transferFromPlayer(founder, capital, id)).thenCompose(result -> afterTransfer(existing, result));
        });
    }
    public CompletionStage<Integer> recoverPendingCapitalizations() {
        return CompletableFuture.supplyAsync(() -> {
            int recovered = 0;
            for (Company company : finance.findLegacyCompaniesWithoutFinance()) { createLegacy(company); recovered++; }
            for (TreasuryOperation operation : finance.findUnsettledOperations()) {
                if (operation.state() == TreasuryOperationState.ESCROW_DEPOSITED) { complete(operation); recovered++; }
                else if (operation.state() == TreasuryOperationState.PREPARED || operation.state() == TreasuryOperationState.PLAYER_WITHDRAWN) { transition(operation, operation.state(), TreasuryOperationState.AMBIGUOUS, "startup cannot prove external transfer outcome"); recovered++; }
                else if (operation.state() == TreasuryOperationState.REFUND_REQUIRED) { refund(operation); recovered++; }
            }
            return recovered;
        }, sql);
    }
    private TreasuryOperation prepare(Company company, UUID founder, Money capital, UUID id) {
        Optional<TreasuryOperation> stored = finance.findById(id); if (stored.isPresent()) return stored.get();
        Instant now = clock.now(); TreasuryOperation operation = new TreasuryOperation(id, company.id(), founder, capital, id.toString(), TreasuryOperationState.PREPARED, now, now);
        transactions.inTransaction(c -> { finance.prepare(c, operation, event(operation, "NONE", TreasuryOperationState.PREPARED, "")); return null; });
        return operation;
    }
    private CompletionStage<Void> afterTransfer(TreasuryOperation operation, EconomyGateway.Result result) {
        if (result.outcome() != EconomyGateway.Outcome.SUCCESS) {
            return CompletableFuture.runAsync(() -> transition(operation, TreasuryOperationState.PREPARED, TreasuryOperationState.AMBIGUOUS, result.message()), sql);
        }
        return CompletableFuture.supplyAsync(() -> { transition(operation, TreasuryOperationState.PREPARED, TreasuryOperationState.PLAYER_WITHDRAWN, ""); transition(operation, TreasuryOperationState.PLAYER_WITHDRAWN, TreasuryOperationState.ESCROW_DEPOSITED, ""); return operation; }, sql)
                .thenCompose(ignored -> CompletableFuture.runAsync(() -> complete(operation), sql))
                .exceptionallyCompose(failure -> CompletableFuture.runAsync(() -> { TreasuryOperation current = finance.findById(operation.id()).orElse(operation); if (current.state() == TreasuryOperationState.ESCROW_DEPOSITED) { transition(current, TreasuryOperationState.ESCROW_DEPOSITED, TreasuryOperationState.REFUND_REQUIRED, failure.getMessage()); refund(current); } }, sql));
    }
    private void complete(TreasuryOperation operation) {
        TreasuryOperation current = finance.findById(operation.id()).orElse(operation);
        if (current.state() == TreasuryOperationState.COMPLETED) return;
        if (current.state() != TreasuryOperationState.ESCROW_DEPOSITED) throw new IllegalStateException("cannot complete from " + current.state());
        Money amount = current.amount(); CompanyCashAccount account = new CompanyCashAccount(current.companyId(), amount, amount, Money.zero(), Money.zero());
        ShareHolding holding = new ShareHolding(current.companyId(), current.playerId(), 1_000, 0);
        transactions.inTransaction(c -> { finance.createCapitalization(c, account, holding, current, event(current, TreasuryOperationState.ESCROW_DEPOSITED.name(), TreasuryOperationState.COMPLETED, "")); return null; });
    }
    private void refund(TreasuryOperation operation) {
        mainThread.submit(() -> escrow.refundToPlayer(operation.playerId(), operation.amount(), operation.id())).thenApply(result -> {
            if (result.outcome() == EconomyGateway.Outcome.SUCCESS) transition(operation, TreasuryOperationState.REFUND_REQUIRED, TreasuryOperationState.REFUNDED, "");
            else transition(operation, TreasuryOperationState.REFUND_REQUIRED, TreasuryOperationState.AMBIGUOUS, result.message());
            return null;
        }).toCompletableFuture().join();
    }
    private void createLegacy(Company company) {
        UUID id = UUID.nameUUIDFromBytes(("legacy-capitalization:" + company.id().value()).getBytes(StandardCharsets.UTF_8));
        if (finance.findById(id).isPresent()) return;
        Instant now = clock.now(); TreasuryOperation operation = new TreasuryOperation(id, company.id(), company.founderId(), company.treasury(), id.toString(), TreasuryOperationState.PREPARED, now, now);
        transactions.inTransaction(c -> { finance.prepare(c, operation, event(operation, "NONE", TreasuryOperationState.PREPARED, "legacy capitalization")); finance.transition(c, id, TreasuryOperationState.PREPARED, TreasuryOperationState.PLAYER_WITHDRAWN, event(operation, "PREPARED", TreasuryOperationState.PLAYER_WITHDRAWN, "historical company")); finance.transition(c, id, TreasuryOperationState.PLAYER_WITHDRAWN, TreasuryOperationState.ESCROW_DEPOSITED, event(operation, "PLAYER_WITHDRAWN", TreasuryOperationState.ESCROW_DEPOSITED, "historical company")); return null; });
        complete(operation);
    }
    private void transition(TreasuryOperation operation, TreasuryOperationState from, TreasuryOperationState to, String reason) {
        transactions.inTransaction(c -> { finance.transition(c, operation.id(), from, to, event(operation, from.name(), to, reason)); return null; });
    }
    private AuditEvent event(TreasuryOperation operation, String from, TreasuryOperationState to, String reason) {
        return new AuditEvent(UUID.randomUUID(), Optional.of(operation.companyId()), Optional.of(operation.playerId()), "COMPANY_CAPITALIZATION_" + to.name(), Map.of("operationId", operation.id().toString(), "fromState", from, "toState", to.name(), "amountMinor", operation.amount().minorUnits(), "reason", reason == null ? "" : reason), clock.now());
    }
}
