package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Non-blocking external legs for the personal securities-cash boundary. */
public interface SecuritiesCashGateway {
    CompletionStage<EconomyGateway.Result> withdrawPlayer(UUID playerId, Money amount);
    CompletionStage<EconomyGateway.Result> depositEscrow(Money amount);
    CompletionStage<EconomyGateway.Result> withdrawEscrow(Money amount);
    CompletionStage<EconomyGateway.Result> depositPlayer(UUID playerId, Money amount);
    CompletionStage<Money> escrowBalance();
}
