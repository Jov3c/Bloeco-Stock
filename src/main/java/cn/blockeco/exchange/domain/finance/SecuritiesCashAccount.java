package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.UUID;

/** Cash held by a player inside the exchange, never their Vault wallet. */
public record SecuritiesCashAccount(UUID playerId, Money available, Money reserved) {
    public SecuritiesCashAccount {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(available, "available").requireNonNegative("available");
        Objects.requireNonNull(reserved, "reserved").requireNonNegative("reserved");
    }
}
