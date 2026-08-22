package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.finance.EscrowReconciliation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashAccount;
import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Authoritative, segregated personal cash balance.  Mutators never open a connection. */
public interface SecuritiesCashRepository {
    Optional<SecuritiesCashAccount> find(UUID playerId);
    void creditAvailable(Connection connection, UUID playerId, Money amount, Instant occurredAt) throws SQLException;
    void reserve(Connection connection, UUID playerId, Money amount) throws SQLException;
    void release(Connection connection, UUID playerId, Money amount) throws SQLException;
    EscrowReconciliation reconcile(Money physicalBalance);
}
