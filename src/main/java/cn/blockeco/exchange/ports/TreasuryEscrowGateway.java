package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.util.UUID;

/** Moves money through the BlockStock-controlled escrow identity. */
public interface TreasuryEscrowGateway {
    EconomyGateway.Result transferFromPlayer(UUID playerId, Money amount, UUID operationId);
    EconomyGateway.Result refundToPlayer(UUID playerId, Money amount, UUID operationId);
}
