package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.ports.RegistrationSagaRepository;
import cn.blockeco.exchange.ports.DuplicateCompanyNameException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

public final class SqlRegistrationSagaRepository implements RegistrationSagaRepository {
    private final DataSource dataSource;
    public SqlRegistrationSagaRepository(DataSource dataSource) { this.dataSource = dataSource; }
    @Override public List<RegistrationSaga> findPreparedBefore(Instant cutoff) {
        return findBefore("PREPARED", cutoff);
    }

    @Override
    public List<RegistrationSaga> findWithdrawnBefore(Instant cutoff) {
        return findBefore("WITHDRAWN", cutoff);
    }

    @Override public List<RegistrationSaga> findRecoveryRecords() {
        String query = "SELECT id, founder_uuid, company_normalized_name, total_withdrawal_minor, state, error_message, created_at, updated_at FROM registration_sagas WHERE state IN ('REFUND_REQUIRED', 'AMBIGUOUS') ORDER BY updated_at";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(query); var rows = statement.executeQuery()) {
            List<RegistrationSaga> sagas = new ArrayList<>();
            while (rows.next()) sagas.add(new RegistrationSaga(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), rows.getString(3), cn.blockeco.exchange.domain.money.Money.ofMinor(rows.getLong(4)), RegistrationSagaState.valueOf(rows.getString(5)), rows.getString(6), Instant.parse(rows.getString(7)), Instant.parse(rows.getString(8))));
            return sagas;
        } catch (SQLException exception) { throw new IllegalStateException("could not read recovery registration sagas", exception); }
    }

    private List<RegistrationSaga> findBefore(String state, Instant cutoff) {
        String query = "SELECT id, founder_uuid, company_normalized_name, total_withdrawal_minor, state, error_message, created_at, updated_at FROM registration_sagas WHERE state = ? AND updated_at < ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, state);
            statement.setString(2, cutoff.toString());
            try (var rows = statement.executeQuery()) {
                List<RegistrationSaga> sagas = new ArrayList<>();
                while (rows.next()) sagas.add(new RegistrationSaga(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), rows.getString(3), cn.blockeco.exchange.domain.money.Money.ofMinor(rows.getLong(4)), RegistrationSagaState.valueOf(rows.getString(5)), rows.getString(6), Instant.parse(rows.getString(7)), Instant.parse(rows.getString(8))));
                return sagas;
            }
        } catch (SQLException exception) { throw new IllegalStateException("could not read stale registration sagas", exception); }
    }
    @Override public void save(Connection connection, RegistrationSaga saga) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO registration_sagas (id, founder_uuid, company_normalized_name, total_withdrawal_minor, state, error_message, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, saga.id().toString()); statement.setString(2, saga.founderId().toString()); statement.setString(3, saga.companyNormalizedName()); statement.setLong(4, saga.totalWithdrawal().minorUnits()); statement.setString(5, saga.state().name()); statement.setString(6, saga.errorMessage()); statement.setString(7, saga.createdAt().toString()); statement.setString(8, saga.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isActiveNameReservationConflict(connection, saga.companyNormalizedName(), exception)) {
                throw new DuplicateCompanyNameException(saga.companyNormalizedName(), exception);
            }
            throw exception;
        }
    }

    private boolean isActiveNameReservationConflict(Connection connection, String normalizedName, SQLException exception)
            throws SQLException {
        if (!(exception instanceof SQLiteException sqlite)
                || sqlite.getResultCode() != SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM registration_sagas
                WHERE company_normalized_name = ?
                  AND state IN ('PREPARED', 'WITHDRAWN', 'REFUND_REQUIRED', 'AMBIGUOUS')
                """)) {
            statement.setString(1, normalizedName);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

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
