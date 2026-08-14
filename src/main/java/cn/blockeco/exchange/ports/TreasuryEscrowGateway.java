package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.util.UUID;

/** Moves money through the BlockStock-controlled escrow identity. */
public interface TreasuryEscrowGateway {
    EconomyGateway.Result withdrawPlayer(UUID playerId, Money amount, UUID operationId);
    EconomyGateway.Result depositEscrow(Money amount, UUID operationId);
    EconomyGateway.Result withdrawEscrow(Money amount, UUID operationId);
    EconomyGateway.Result refundPlayer(UUID playerId, Money amount, UUID operationId);
}
