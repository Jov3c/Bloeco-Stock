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
    @Override public EconomyGateway.Result withdrawPlayer(UUID playerId, Money amount, UUID operationId) { return onMain(() -> economy.withdraw(playerId, amount)); }
    @Override public EconomyGateway.Result depositEscrow(Money amount, UUID operationId) { return onMain(() -> economy.deposit(escrowId, amount)); }
    @Override public EconomyGateway.Result withdrawEscrow(Money amount, UUID operationId) { return onMain(() -> economy.withdraw(escrowId, amount)); }
    @Override public EconomyGateway.Result refundPlayer(UUID playerId, Money amount, UUID operationId) { return onMain(() -> economy.deposit(playerId, amount)); }
    private EconomyGateway.Result onMain(java.util.function.Supplier<EconomyGateway.Result> work) { return mainThread.submit(work).toCompletableFuture().join(); }
}
