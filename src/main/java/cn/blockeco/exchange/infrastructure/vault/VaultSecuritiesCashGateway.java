package cn.blockeco.exchange.infrastructure.vault;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.SecuritiesCashGateway;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Marshals every Bukkit/Vault action onto the Paper main thread without blocking it. */
public final class VaultSecuritiesCashGateway implements SecuritiesCashGateway {
    private final EconomyGateway economy; private final MainThreadExecutor main; private final UUID escrowId;
    public VaultSecuritiesCashGateway(EconomyGateway economy, MainThreadExecutor main, UUID escrowId) { this.economy=Objects.requireNonNull(economy); this.main=Objects.requireNonNull(main); this.escrowId=Objects.requireNonNull(escrowId); }
    @Override public CompletionStage<EconomyGateway.Result> withdrawPlayer(UUID id, Money amount) { return main.submit(() -> economy.withdraw(id, amount)); }
    @Override public CompletionStage<EconomyGateway.Result> depositEscrow(Money amount) { return main.submit(() -> economy.deposit(escrowId, amount)); }
    @Override public CompletionStage<EconomyGateway.Result> withdrawEscrow(Money amount) { return main.submit(() -> economy.withdraw(escrowId, amount)); }
    @Override public CompletionStage<EconomyGateway.Result> depositPlayer(UUID id, Money amount) { return main.submit(() -> economy.deposit(id, amount)); }
    @Override public CompletionStage<Money> escrowBalance() { return main.submit(() -> economy.balance(escrowId)); }
}
