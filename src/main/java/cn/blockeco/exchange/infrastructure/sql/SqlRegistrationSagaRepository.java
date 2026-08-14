package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.ports.RegistrationSagaRepository;
import cn.blockeco.exchange.ports.DuplicateCompanyNameException;
import cn.blockeco.exchange.ports.AppClock;
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
    private final AppClock clock;
    public SqlRegistrationSagaRepository(DataSource dataSource, AppClock clock) { this.dataSource = dataSource; this.clock = clock; }
    @Override public List<RegistrationSaga> findPreparedBefore(Instant cutoff) {
        return findBefore("PREPARED", cutoff);
    }

    @Override
    public List<RegistrationSaga> findWithdrawnBefore(Instant cutoff) {
        return findBefore("WITHDRAWN", cutoff, false);
    }

    @Override public List<RegistrationSaga> findEscrowWithdrawnBefore(Instant cutoff) { return findBefore("WITHDRAWN", cutoff, true); }

    @Override public List<RegistrationSaga> findRecoveryRecords() {
        String query = "SELECT * FROM registration_sagas WHERE state IN ('REFUND_REQUIRED', 'AMBIGUOUS') ORDER BY updated_at";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(query); var rows = statement.executeQuery()) {
            List<RegistrationSaga> sagas = new ArrayList<>();
            while (rows.next()) sagas.add(saga(rows));
            return sagas;
        } catch (SQLException exception) { throw new IllegalStateException("could not read recovery registration sagas", exception); }
    }

    private List<RegistrationSaga> findBefore(String state, Instant cutoff) { return findBefore(state, cutoff, null); }
    private List<RegistrationSaga> findBefore(String state, Instant cutoff, Boolean requiresEscrow) {
        String query = "SELECT * FROM registration_sagas WHERE state = ? AND updated_at < ?" + (requiresEscrow == null ? "" : " AND requires_escrow = ?");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, state);
            statement.setString(2, cutoff.toString());
            if (requiresEscrow != null) statement.setBoolean(3, requiresEscrow);
            try (var rows = statement.executeQuery()) {
                List<RegistrationSaga> sagas = new ArrayList<>();
                while (rows.next()) sagas.add(saga(rows));
                return sagas;
            }
        } catch (SQLException exception) { throw new IllegalStateException("could not read stale registration sagas", exception); }
    }
    @Override public void save(Connection connection, RegistrationSaga saga) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO registration_sagas (id, founder_uuid, company_normalized_name, total_withdrawal_minor, state, error_message, created_at, updated_at, requires_escrow) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, saga.id().toString()); statement.setString(2, saga.founderId().toString()); statement.setString(3, saga.companyNormalizedName()); statement.setLong(4, saga.totalWithdrawal().minorUnits()); statement.setString(5, saga.state().name()); statement.setString(6, saga.errorMessage()); statement.setString(7, saga.createdAt().toString()); statement.setString(8, saga.updatedAt().toString()); statement.setBoolean(9, saga.requiresEscrow());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isActiveNameReservationConflict(connection, saga.companyNormalizedName(), exception)) {
                throw new DuplicateCompanyNameException(saga.companyNormalizedName(), exception);
            }
            throw exception;
        }
    }

    private static RegistrationSaga saga(java.sql.ResultSet rows) throws SQLException { return new RegistrationSaga(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("founder_uuid")), rows.getString("company_normalized_name"), cn.blockeco.exchange.domain.money.Money.ofMinor(rows.getLong("total_withdrawal_minor")), RegistrationSagaState.valueOf(rows.getString("state")), rows.getString("error_message"), Instant.parse(rows.getString("created_at")), Instant.parse(rows.getString("updated_at")), rows.getBoolean("requires_escrow")); }

    private boolean isActiveNameReservationConflict(Connection connection, String normalizedName, SQLException exception)
            throws SQLException {
        if (!(exception instanceof SQLiteException sqlite)
                || sqlite.getResultCode() != SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM registration_sagas
                WHERE company_normalized_name = ?
                  AND state IN ('PREPARED', 'WITHDRAWN', 'ESCROW_DEPOSITED', 'REFUND_REQUIRED', 'AMBIGUOUS')
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
            WHERE id = ? AND state = ?
            """;

    @Override
    public void transition(Connection connection, UUID id, RegistrationSagaState expectedFromState, RegistrationSagaState state, String errorMessage)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRANSITION)) {
            statement.setString(1, state.name());
            statement.setString(2, errorMessage);
            statement.setString(3, clock.now().toString());
            statement.setString(4, id.toString());
            statement.setString(5, expectedFromState.name());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("registration saga state conflict: " + id + " expected " + expectedFromState);
            }
        }
    }
}
