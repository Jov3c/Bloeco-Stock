package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

public interface RegistrationSagaRepository {

    void transition(Connection connection, UUID id, RegistrationSagaState state, String errorMessage) throws SQLException;
}
