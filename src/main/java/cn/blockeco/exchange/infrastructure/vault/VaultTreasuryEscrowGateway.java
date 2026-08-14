package cn.blockeco.exchange.infrastructure.vault;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.TreasuryEscrowGateway;
import java.util.UUID;

/** Vault adapter for the reserved, non-player escrow identity. */
public final class VaultTreasuryEscrowGateway implements TreasuryEscrowGateway {
    private final EconomyGateway economy; private final MainThreadExecutor mainThread; private final UUID escrowId;
    public VaultTreasuryEscrowGateway(EconomyGateway economy, MainThreadExecutor mainThread, UUID escrowId) { this.economy = economy; this.mainThread = mainThread; this.escrowId = escrowId; }
    @Override public EconomyGateway.Result transferFromPlayer(UUID playerId, Money amount, UUID operationId) {
        EconomyGateway.Result withdrawal = onMain(() -> economy.withdraw(playerId, amount)); if (withdrawal.outcome() != EconomyGateway.Outcome.SUCCESS) return withdrawal;
        EconomyGateway.Result deposit = onMain(() -> economy.deposit(escrowId, amount)); if (deposit.outcome() == EconomyGateway.Outcome.SUCCESS) return deposit;
        EconomyGateway.Result compensation = onMain(() -> economy.deposit(playerId, amount));
        return compensation.outcome() == EconomyGateway.Outcome.SUCCESS ? EconomyGateway.Result.providerFailure("escrow deposit failed; player compensated: " + deposit.message()) : EconomyGateway.Result.providerFailure("escrow deposit and compensation failed: " + deposit.message() + "; " + compensation.message());
    }
    @Override public EconomyGateway.Result refundToPlayer(UUID playerId, Money amount, UUID operationId) { return onMain(() -> economy.withdraw(escrowId, amount)).outcome() == EconomyGateway.Outcome.SUCCESS ? onMain(() -> economy.deposit(playerId, amount)) : EconomyGateway.Result.providerFailure("escrow withdrawal failed"); }
    private EconomyGateway.Result onMain(java.util.function.Supplier<EconomyGateway.Result> work) { return mainThread.submit(work).toCompletableFuture().join(); }
}
