package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.EscrowReconciliation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperation;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Read-only diagnostic view. It never calls Vault, retries, refunds, or resolves ambiguity. */
public final class SecondaryMarketRecoveryService {
    private final SecuritiesCashRepository cash;
    private final Supplier<? extends List<LegacyRecoveryIssue>> legacyIssues;
    private final Executor sql;

    public SecondaryMarketRecoveryService(SecuritiesCashRepository cash, Executor sql) {
        this(cash, List::of, sql);
    }

    /**
     * Legacy records are supplied by the bootstrapper after its capitalization/IPO recovery passes.
     * Keeping them as an input avoids a second repository connection while a single-connection
     * SQLite transaction is still in progress.
     */
    public SecondaryMarketRecoveryService(SecuritiesCashRepository cash,
                                          Supplier<? extends List<LegacyRecoveryIssue>> legacyIssues,
                                          Executor sql) {
        this.cash = Objects.requireNonNull(cash);
        this.legacyIssues = Objects.requireNonNull(legacyIssues);
        this.sql = Objects.requireNonNull(sql);
    }

    /** Read-only inspection. It never invokes Vault, retries an operation, or changes a state. */
    public CompletionStage<RecoverySnapshot> inspect(Money physicalBalance) {
        return CompletableFuture.supplyAsync(() -> {
            EscrowReconciliation reconciliation = cash.reconcile(physicalBalance);
            List<SecuritiesCashOperation> operations = List.copyOf(cash.findRecoveryCandidates());
            List<SecuritiesCashOperation> finalStages = operations.stream()
                    .filter(SecondaryMarketRecoveryService::isDurableFinalStage).toList();
            List<SecuritiesCashOperation> unresolved = operations.stream()
                    .filter(operation -> !isDurableFinalStage(operation)).toList();
            List<LegacyRecoveryIssue> legacy = List.copyOf(legacyIssues.get());
            // A zero cash difference is evidence only about the total. It must never clear an
            // operation whose individual external result is unknown.
            boolean blocked = !unresolved.isEmpty() || !legacy.isEmpty()
                    || reconciliation.confirmedDifference().minorUnits() != 0;
            return new RecoverySnapshot(operations, finalStages, unresolved, legacy, reconciliation, blocked);
        }, sql);
    }

    private static boolean isDurableFinalStage(SecuritiesCashOperation operation) {
        return (operation.direction() == cn.blockeco.exchange.domain.finance.SecuritiesCashDirection.DEPOSIT
                && operation.state() == cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState.ESCROW_DEPOSITED)
                || (operation.direction() == cn.blockeco.exchange.domain.finance.SecuritiesCashDirection.WITHDRAW
                && operation.state() == cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState.PLAYER_DEPOSITED);
    }

    /** A normalized, read-only legacy ambiguity from capitalization or an IPO subscription. */
    public record LegacyRecoveryIssue(String source, UUID operationId, Money amount, String state,
                                      String lastConfirmedStage, String reason) {
        public LegacyRecoveryIssue {
            source = requireText(source, "source");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(amount, "amount");
            state = requireText(state, "state");
            lastConfirmedStage = lastConfirmedStage == null ? "" : lastConfirmedStage;
            reason = reason == null ? "" : reason;
        }
        private static String requireText(String value, String label) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
            return value;
        }
    }

    public record RecoverySnapshot(List<SecuritiesCashOperation> operations,
                                   List<SecuritiesCashOperation> finalStageOperations,
                                   List<SecuritiesCashOperation> unresolvedCashOperations,
                                   List<LegacyRecoveryIssue> legacyIssues,
                                   EscrowReconciliation reconciliation,
                                   boolean mutationsBlocked) {
        public RecoverySnapshot {
            operations = List.copyOf(operations);
            finalStageOperations = List.copyOf(finalStageOperations);
            unresolvedCashOperations = List.copyOf(unresolvedCashOperations);
            legacyIssues = List.copyOf(legacyIssues);
            Objects.requireNonNull(reconciliation, "reconciliation");
        }
    }
}
