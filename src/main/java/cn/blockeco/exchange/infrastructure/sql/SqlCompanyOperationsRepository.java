package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.OperatingEventKind;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import cn.blockeco.exchange.ports.CompanyOperationsRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlCompanyOperationsRepository implements CompanyOperationsRepository {
    private final DataSource dataSource;

    public SqlCompanyOperationsRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public RecordResult record(Connection connection, AssetBinding binding, VerifiedOperatingEvent event, Instant recordedAt) throws SQLException {
        requireTransaction(connection);
        if (!binding.adapterId().equals(event.adapterId())) throw new IllegalArgumentException("event adapter does not match binding");
        if (!isActiveBinding(connection, binding)) throw new IllegalArgumentException("binding is not active for its company");
        if (!claimEvent(connection, binding, event, recordedAt)) return RecordResult.DUPLICATE;

        Balance balance = balance(connection, binding.companyId());
        long cash = event.kind() == OperatingEventKind.INCOME
                ? Math.addExact(balance.cash(), event.amount())
                : subtractUnreservedCash(balance, event.amount());
        long retained = balance.retainedEarnings();
        long loss = balance.accumulatedLoss();
        if (event.kind() == OperatingEventKind.INCOME) {
            long lossOffset = Math.min(loss, event.amount());
            loss -= lossOffset;
            retained = Math.addExact(retained, event.amount() - lossOffset);
        } else {
            long retainedUsed = Math.min(retained, event.amount());
            retained -= retainedUsed;
            loss = Math.addExact(loss, event.amount() - retainedUsed);
        }
        updateBalance(connection, binding.companyId(), cash, retained, loss);
        appendAudit(connection, binding.companyId(), event, recordedAt);
        appendTreasuryLedger(connection, binding.companyId(), event.kind() == OperatingEventKind.INCOME ? event.amount() : -event.amount(), recordedAt);
        return RecordResult.RECORDED;
    }

    @Override
    public Optional<FinancialSnapshot> snapshot(CompanyId companyId) {
        String sql = """
                SELECT ca.cash_minor, ca.retained_earnings_minor, ca.accumulated_loss_minor,
                  COALESCE(SUM(CASE WHEN oe.kind = 'INCOME' THEN oe.amount_minor ELSE 0 END), 0),
                  COALESCE(SUM(CASE WHEN oe.kind = 'EXPENSE' THEN oe.amount_minor ELSE 0 END), 0)
                FROM company_cash_accounts ca
                LEFT JOIN company_operating_events oe ON oe.company_id = ca.company_id
                WHERE ca.company_id = ?
                GROUP BY ca.company_id
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, companyId.value().toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new FinancialSnapshot(companyId, rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5))) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("could not read company financial snapshot", exception);
        }
    }

    private static boolean isActiveBinding(Connection connection, AssetBinding binding) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM asset_bindings WHERE id = ? AND company_id = ? AND adapter_id = ? AND state = 'ACTIVE'")) {
            statement.setString(1, binding.id().toString());
            statement.setString(2, binding.companyId().value().toString());
            statement.setString(3, binding.adapterId());
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static boolean claimEvent(Connection connection, AssetBinding binding, VerifiedOperatingEvent event, Instant recordedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_operating_events (id, company_id, binding_id, adapter_id, external_event_key, kind, amount_minor, occurred_at, recorded_at, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(adapter_id, external_event_key) DO NOTHING
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, binding.companyId().value().toString());
            statement.setString(3, binding.id().toString());
            statement.setString(4, event.adapterId());
            statement.setString(5, event.externalEventKey());
            statement.setString(6, event.kind().name());
            statement.setLong(7, event.amount());
            statement.setString(8, event.occurredAt().toString());
            statement.setString(9, recordedAt.toString());
            statement.setString(10, "{\"summary\":\"" + escapeJson(event.displaySummary()) + "\"}");
            return statement.executeUpdate() == 1;
        }
    }

    private static Balance balance(Connection connection, CompanyId companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT cash_minor, reserved_minor, retained_earnings_minor, accumulated_loss_minor FROM company_cash_accounts WHERE company_id = ?")) {
            statement.setString(1, companyId.value().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("company cash account does not exist");
                return new Balance(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4));
            }
        }
    }

    private static long subtractUnreservedCash(Balance balance, long amount) {
        long available = Math.subtractExact(balance.cash(), balance.reserved());
        if (available < amount) throw new IllegalArgumentException("insufficient unreserved company cash");
        return balance.cash() - amount;
    }

    private static void updateBalance(Connection connection, CompanyId companyId, long cash, long retained, long loss) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE company_cash_accounts SET cash_minor = ?, retained_earnings_minor = ?, accumulated_loss_minor = ? WHERE company_id = ?")) {
            statement.setLong(1, cash); statement.setLong(2, retained); statement.setLong(3, loss); statement.setString(4, companyId.value().toString());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("company cash account state conflict");
        }
    }

    private static void appendAudit(Connection connection, CompanyId companyId, VerifiedOperatingEvent event, Instant recordedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at) VALUES (?, ?, NULL, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, companyId.value().toString());
            statement.setString(3, event.kind() == OperatingEventKind.INCOME ? "OPERATING_INCOME_RECORDED" : "OPERATING_EXPENSE_RECORDED");
            statement.setString(4, "{\"adapterId\":\"" + escapeJson(event.adapterId()) + "\",\"externalEventKey\":\"" + escapeJson(event.externalEventKey()) + "\",\"amountMinor\":" + event.amount() + "}");
            statement.setString(5, recordedAt.toString());
            statement.executeUpdate();
        }
    }

    private static void appendTreasuryLedger(Connection connection, CompanyId companyId, long amount, Instant recordedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO escrow_ledger_entries (id, liability_kind, company_id, player_uuid, amount_minor, operation_id, trade_id, occurred_at) VALUES (?, 'COMPANY_TREASURY', ?, NULL, ?, NULL, NULL, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, companyId.value().toString());
            statement.setLong(3, amount);
            statement.setString(4, recordedAt.toString());
            statement.executeUpdate();
        }
    }

    private static String escapeJson(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private static void requireTransaction(Connection connection) throws SQLException { if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required"); }
    private record Balance(long cash, long reserved, long retainedEarnings, long accumulatedLoss) { }
}
