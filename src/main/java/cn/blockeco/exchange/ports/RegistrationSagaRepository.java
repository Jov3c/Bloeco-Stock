package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.time.Instant;
import java.util.List;

public interface RegistrationSagaRepository {

    void save(Connection connection, RegistrationSaga saga) throws SQLException;

    List<RegistrationSaga> findPreparedBefore(Instant cutoff);

    List<RegistrationSaga> findWithdrawnBefore(Instant cutoff);

    /** Escrow-backed withdrawals cannot be compensated automatically after a crash. */
    default List<RegistrationSaga> findEscrowWithdrawnBefore(Instant cutoff) { return List.of(); }

    /** Administrative visibility only; no recovery action is implied. */
    default List<RegistrationSaga> findRecoveryRecords() { return List.of(); }

    void transition(Connection connection, UUID id, RegistrationSagaState expectedFromState, RegistrationSagaState state, String errorMessage) throws SQLException;
}
