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
            return CompletableFuture.supplyAsync(() -> escrow.withdrawPlayer(founder, capital, id)).thenCompose(result -> afterWithdrawal(existing, result));
        });
    }
    public CompletionStage<Integer> recoverPendingCapitalizations() {
        return CompletableFuture.supplyAsync(() -> {
            int recovered = 0;
            for (Company company : finance.findLegacyCompaniesWithoutFinance()) { createLegacy(company); recovered++; }
            for (TreasuryOperation operation : finance.findUnsettledOperations()) {
                if (operation.state() == TreasuryOperationState.ESCROW_DEPOSITED) { complete(operation); recovered++; }
                else if (operation.state() == TreasuryOperationState.PREPARED || operation.state() == TreasuryOperationState.PLAYER_WITHDRAWN || operation.state() == TreasuryOperationState.REFUND_REQUIRED) { transition(operation, operation.state(), TreasuryOperationState.AMBIGUOUS, "startup cannot prove external transfer outcome; no Vault action retried"); recovered++; }
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
    private CompletionStage<Void> afterWithdrawal(TreasuryOperation operation, EconomyGateway.Result result) {
        if (result.outcome() != EconomyGateway.Outcome.SUCCESS) {
            return CompletableFuture.runAsync(() -> transition(operation, TreasuryOperationState.PREPARED, TreasuryOperationState.AMBIGUOUS, result.message()), sql);
        }
        return CompletableFuture.runAsync(() -> transition(operation, TreasuryOperationState.PREPARED, TreasuryOperationState.PLAYER_WITHDRAWN, "confirmed player withdrawal"), sql)
                .thenCompose(ignored -> CompletableFuture.supplyAsync(() -> escrow.depositEscrow(operation.amount(), operation.id())))
                .thenCompose(deposit -> afterEscrowDeposit(operation, deposit));
    }
    private CompletionStage<Void> afterEscrowDeposit(TreasuryOperation operation, EconomyGateway.Result result) {
        if (result.outcome() != EconomyGateway.Outcome.SUCCESS) return CompletableFuture.runAsync(() -> transition(operation, TreasuryOperationState.PLAYER_WITHDRAWN, TreasuryOperationState.AMBIGUOUS, result.message()), sql);
        return CompletableFuture.runAsync(() -> completeAfterEscrowDeposit(operation, TreasuryOperationState.PLAYER_WITHDRAWN), sql)
                .exceptionallyCompose(failure -> CompletableFuture.runAsync(() -> markDepositAmbiguous(operation, failure), sql));
    }
    private void complete(TreasuryOperation operation) {
        TreasuryOperation current = finance.findById(operation.id()).orElse(operation);
        if (current.state() == TreasuryOperationState.COMPLETED) return;
        if (current.state() != TreasuryOperationState.ESCROW_DEPOSITED) throw new IllegalStateException("cannot complete from " + current.state());
        Money amount = current.amount(); CompanyCashAccount account = new CompanyCashAccount(current.companyId(), amount, amount, Money.zero(), Money.zero());
        ShareHolding holding = new ShareHolding(current.companyId(), current.playerId(), 1_000, 0);
        transactions.inTransaction(c -> { finance.createCapitalization(c, account, holding, current, event(current, TreasuryOperationState.ESCROW_DEPOSITED.name(), TreasuryOperationState.COMPLETED, "")); return null; });
    }
    private void createLegacy(Company company) {
        UUID id = UUID.nameUUIDFromBytes(("legacy-capitalization:" + company.id().value()).getBytes(StandardCharsets.UTF_8));
        TreasuryOperation operation = finance.findById(id).orElseGet(() -> prepareLegacy(company, id));
        if (operation.state() != TreasuryOperationState.PREPARED) return;
        transition(operation, TreasuryOperationState.PREPARED, TreasuryOperationState.PLAYER_WITHDRAWN, "已确认历史玩家扣款；不会再次扣款");
        try {
            EconomyGateway.Result deposit = escrow.depositEscrow(operation.amount(), operation.id());
            if (deposit.outcome() != EconomyGateway.Outcome.SUCCESS) { transition(operation, TreasuryOperationState.PLAYER_WITHDRAWN, TreasuryOperationState.AMBIGUOUS, deposit.message()); return; }
            completeAfterEscrowDeposit(operation, TreasuryOperationState.PLAYER_WITHDRAWN);
        } catch (RuntimeException failure) { markDepositAmbiguous(operation, failure); }
    }
    private TreasuryOperation prepareLegacy(Company company, UUID id) {
        Instant now = clock.now(); TreasuryOperation operation = new TreasuryOperation(id, company.id(), company.founderId(), company.treasury(), id.toString(), TreasuryOperationState.PREPARED, now, now);
        transactions.inTransaction(c -> { finance.prepare(c, operation, event(operation, "NONE", TreasuryOperationState.PREPARED, "遗留公司资本化")); return null; });
        return operation;
    }
    private void completeAfterEscrowDeposit(TreasuryOperation operation, TreasuryOperationState expected) {
        Money amount = operation.amount(); CompanyCashAccount account = new CompanyCashAccount(operation.companyId(), amount, amount, Money.zero(), Money.zero()); ShareHolding holding = new ShareHolding(operation.companyId(), operation.playerId(), 1_000, 0);
        transactions.inTransaction(c -> { finance.transition(c, operation.id(), expected, TreasuryOperationState.ESCROW_DEPOSITED, event(operation, expected.name(), TreasuryOperationState.ESCROW_DEPOSITED, "confirmed escrow deposit")); finance.createCapitalization(c, account, holding, operation, event(operation, TreasuryOperationState.ESCROW_DEPOSITED.name(), TreasuryOperationState.COMPLETED, "")); return null; });
    }
    private void markDepositAmbiguous(TreasuryOperation operation, Throwable failure) {
        TreasuryOperation current = finance.findById(operation.id()).orElse(operation);
        if (current.state() == TreasuryOperationState.PLAYER_WITHDRAWN || current.state() == TreasuryOperationState.ESCROW_DEPOSITED) transition(current, current.state(), TreasuryOperationState.AMBIGUOUS, "confirmed escrow deposit could not be recorded: " + failure.getMessage());
    }
    private void transition(TreasuryOperation operation, TreasuryOperationState from, TreasuryOperationState to, String reason) {
        transactions.inTransaction(c -> { finance.transition(c, operation.id(), from, to, event(operation, from.name(), to, reason)); return null; });
    }
    private AuditEvent event(TreasuryOperation operation, String from, TreasuryOperationState to, String reason) {
        return new AuditEvent(UUID.randomUUID(), Optional.of(operation.companyId()), Optional.of(operation.playerId()), "COMPANY_CAPITALIZATION_" + to.name(), Map.of("operationId", operation.id().toString(), "fromState", from, "toState", to.name(), "amountMinor", operation.amount().minorUnits(), "reason", reason == null ? "" : reason), clock.now());
    }
}
