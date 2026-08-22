package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Non-blocking external legs for the personal securities-cash boundary. */
public interface SecuritiesCashGateway {
    CompletionStage<EconomyGateway.Result> withdrawPlayer(UUID playerId, Money amount);
    CompletionStage<EconomyGateway.Result> depositEscrow(Money amount);
    CompletionStage<EconomyGateway.Result> withdrawEscrow(Money amount);
    CompletionStage<EconomyGateway.Result> depositPlayer(UUID playerId, Money amount);
    CompletionStage<Money> escrowBalance();
    default CompletionStage<EconomyGateway.Result> withdrawPlayer(UUID playerId, Money amount, BooleanSupplier guard) { if(!guard.getAsBoolean())return java.util.concurrent.CompletableFuture.completedFuture(EconomyGateway.Result.notCalledFailure("runtime stopped before provider call"));return withdrawPlayer(playerId,amount); }
    default CompletionStage<EconomyGateway.Result> depositEscrow(Money amount, BooleanSupplier guard) { if(!guard.getAsBoolean())return java.util.concurrent.CompletableFuture.completedFuture(EconomyGateway.Result.notCalledFailure("runtime stopped before provider call"));return depositEscrow(amount); }
    default CompletionStage<EconomyGateway.Result> withdrawEscrow(Money amount, BooleanSupplier guard) { if(!guard.getAsBoolean())return java.util.concurrent.CompletableFuture.completedFuture(EconomyGateway.Result.notCalledFailure("runtime stopped before provider call"));return withdrawEscrow(amount); }
    default CompletionStage<EconomyGateway.Result> depositPlayer(UUID playerId, Money amount, BooleanSupplier guard) { if(!guard.getAsBoolean())return java.util.concurrent.CompletableFuture.completedFuture(EconomyGateway.Result.notCalledFailure("runtime stopped before provider call"));return depositPlayer(playerId,amount); }
}
