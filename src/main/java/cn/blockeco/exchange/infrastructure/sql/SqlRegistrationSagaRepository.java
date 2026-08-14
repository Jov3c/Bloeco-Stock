package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import cn.blockeco.exchange.ports.RegistrationSagaRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public final class SqlRegistrationSagaRepository implements RegistrationSagaRepository {

    private static final String TRANSITION = """
            UPDATE registration_sagas
            SET state = ?, error_message = ?, updated_at = ?
            WHERE id = ?
            """;

    @Override
    public void transition(Connection connection, UUID id, RegistrationSagaState state, String errorMessage)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRANSITION)) {
            statement.setString(1, state.name());
            statement.setString(2, errorMessage);
            statement.setString(3, Instant.now().toString());
            statement.setString(4, id.toString());
            statement.executeUpdate();
        }
    }
}
