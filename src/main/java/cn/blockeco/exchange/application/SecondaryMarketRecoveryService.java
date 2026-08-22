package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.EscrowReconciliation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperation;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Read-only diagnostic view. It never calls Vault, retries, refunds, or resolves ambiguity. */
public final class SecondaryMarketRecoveryService {
    private final SecuritiesCashRepository cash; private final Executor sql;
    public SecondaryMarketRecoveryService(SecuritiesCashRepository cash, Executor sql) { this.cash=Objects.requireNonNull(cash); this.sql=Objects.requireNonNull(sql); }
    public CompletionStage<RecoverySnapshot> inspect(Money physicalBalance) { return CompletableFuture.supplyAsync(() -> { EscrowReconciliation reconciliation=cash.reconcile(physicalBalance); List<SecuritiesCashOperation> operations=List.copyOf(cash.findRecoveryCandidates()); return new RecoverySnapshot(operations,reconciliation, !operations.isEmpty() || reconciliation.confirmedDifference().minorUnits()!=0); },sql); }
    public record RecoverySnapshot(List<SecuritiesCashOperation> operations, EscrowReconciliation reconciliation, boolean mutationsBlocked) { public RecoverySnapshot { operations=List.copyOf(operations); } }
}
