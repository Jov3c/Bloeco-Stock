package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Durable intent and confirmations for the one-off server-funded bluechip liquidity injection. */
public interface BluechipBootstrapFundingRepository {
    Optional<Funding> find(UUID id);
    void prepare(Connection connection, Funding funding) throws SQLException;
    void transition(Connection connection, UUID id, State expected, State next, String detail, Instant at) throws SQLException;
    void complete(Connection connection, UUID id, Instant at) throws SQLException;

    enum State { PREPARED, SOURCE_CREDIT_REQUESTED, SOURCE_CREDITED, SOURCE_DEBIT_REQUESTED, SOURCE_DEBITED, ESCROW_DEPOSIT_REQUESTED, ESCROW_DEPOSITED, COMPLETED, AMBIGUOUS }
    record Funding(UUID id, UUID systemAccountId, Money amount, State state, String detail, Instant createdAt, Instant updatedAt) { }
}
