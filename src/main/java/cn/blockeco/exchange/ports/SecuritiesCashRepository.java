package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.finance.EscrowReconciliation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashAccount;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperation;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

/** Authoritative, segregated personal cash balance.  Mutators never open a connection. */
public interface SecuritiesCashRepository {
    Optional<SecuritiesCashAccount> find(UUID playerId);
    void creditAvailable(Connection connection, UUID playerId, Money amount, Instant occurredAt) throws SQLException;
    void reserve(Connection connection, UUID playerId, Money amount) throws SQLException;
    void release(Connection connection, UUID playerId, Money amount) throws SQLException;
    void prepareOperation(Connection connection, SecuritiesCashOperation operation) throws SQLException;
    void transitionOperation(Connection connection, UUID operationId, SecuritiesCashOperationState expected,
            SecuritiesCashOperationState state, SecuritiesCashOperationState confirmedStage, String detail, Instant occurredAt) throws SQLException;
    void completeDeposit(Connection connection, SecuritiesCashOperation operation, Instant occurredAt) throws SQLException;
    void completeWithdrawal(Connection connection, SecuritiesCashOperation operation, Instant occurredAt) throws SQLException;
    Optional<SecuritiesCashOperation> findOperation(UUID operationId);
    Optional<SecuritiesCashOperation> findActiveOperation(UUID playerId);
    List<SecuritiesCashOperation> findRecoveryCandidates();
    EscrowReconciliation reconcile(Money physicalBalance);
}
