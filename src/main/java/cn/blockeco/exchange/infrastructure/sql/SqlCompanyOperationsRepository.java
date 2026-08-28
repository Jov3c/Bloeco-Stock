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
import java.sql.Savepoint;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlCompanyOperationsRepository implements CompanyOperationsRepository {
    private final DataSource dataSource;

    public SqlCompanyOperationsRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public RecordResult record(Connection connection, AssetBinding binding, VerifiedOperatingEvent event, Instant recordedAt) throws SQLException {
        requireTransaction(connection);
        Savepoint savepoint = connection.setSavepoint();
        try {
            if (!binding.adapterId().equals(event.adapterId())) throw new IllegalArgumentException("event adapter does not match binding");
            if (!isActiveBinding(connection, binding)) throw new IllegalArgumentException("binding is not active for its company");
            if (!claimEvent(connection, binding, event, recordedAt)) {
                connection.releaseSavepoint(savepoint);
                return RecordResult.DUPLICATE;
            }

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
            connection.releaseSavepoint(savepoint);
            return RecordResult.RECORDED;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
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

    @Override
    public boolean generateMonthlyReport(Connection connection, CompanyId companyId, YearMonth month, ZoneId zone, Instant generatedAt) throws SQLException {
        requireTransaction(connection);
        ZonedDateTime start = month.atDay(1).atStartOfDay(zone);
        Instant periodStart = start.toInstant();
        Instant periodEnd = start.plusMonths(1).toInstant();
        long income = periodTotal(connection, companyId, periodStart, periodEnd, "INCOME");
        long expense = periodTotal(connection, companyId, periodStart, periodEnd, "EXPENSE");
        Balance balance = balance(connection, companyId);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO company_monthly_reports (company_id, period_start, period_end, income_minor, expense_minor, net_profit_minor, retained_earnings_minor, cash_minor, generated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(company_id, period_start) DO NOTHING
                """)) {
            insert.setString(1, companyId.value().toString()); insert.setString(2, periodStart.toString()); insert.setString(3, periodEnd.toString());
            insert.setLong(4, income); insert.setLong(5, expense); insert.setLong(6, Math.subtractExact(income, expense));
            insert.setLong(7, balance.retainedEarnings()); insert.setLong(8, balance.cash()); insert.setString(9, generatedAt.toString());
            if (insert.executeUpdate() == 0) return false;
        }
        String marker = "MONTHLY_REPORT:" + month;
        try (PreparedStatement announcement = connection.prepareStatement("INSERT INTO company_announcements (id, company_id, offering_id, body, created_at) VALUES (?, ?, NULL, ?, ?)");
             PreparedStatement audit = connection.prepareStatement("INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at) VALUES (?, ?, NULL, 'MONTHLY_REPORT_PUBLISHED', ?, ?)")) {
            announcement.setString(1, UUID.nameUUIDFromBytes((companyId.value() + ":monthly-report:" + month).getBytes(StandardCharsets.UTF_8)).toString()); announcement.setString(2, companyId.value().toString()); announcement.setString(3, marker); announcement.setString(4, generatedAt.toString()); announcement.executeUpdate();
            audit.setString(1, UUID.randomUUID().toString()); audit.setString(2, companyId.value().toString()); audit.setString(3, "{\"periodStart\":\"" + periodStart + "\"}"); audit.setString(4, generatedAt.toString()); audit.executeUpdate();
        }
        return true;
    }

    @Override public List<MonthlyReport> recentReports(CompanyId companyId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 6)); List<MonthlyReport> reports = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT period_start, period_end, income_minor, expense_minor, net_profit_minor, retained_earnings_minor, cash_minor, generated_at FROM company_monthly_reports WHERE company_id = ? ORDER BY period_start DESC LIMIT ?")) {
            statement.setString(1, companyId.value().toString()); statement.setInt(2, bounded); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) reports.add(new MonthlyReport(companyId, Instant.parse(rows.getString(1)), Instant.parse(rows.getString(2)), rows.getLong(3), rows.getLong(4), rows.getLong(5), rows.getLong(6), rows.getLong(7), Instant.parse(rows.getString(8)))); }
            return List.copyOf(reports);
        } catch (SQLException exception) { throw new IllegalStateException("could not read company monthly reports", exception); }
    }

    @Override public List<CompanyId> listedCompanyIds() {
        List<CompanyId> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT c.id FROM companies c JOIN stock_listings sl ON sl.company_id = c.id JOIN company_cash_accounts ca ON ca.company_id = c.id LEFT JOIN bluechip_companies bc ON bc.company_id = c.id WHERE c.status = 'LISTED' AND bc.company_id IS NULL ORDER BY c.id"); ResultSet rows = statement.executeQuery()) { while (rows.next()) ids.add(new CompanyId(UUID.fromString(rows.getString(1)))); return List.copyOf(ids); }
        catch (SQLException exception) { throw new IllegalStateException("could not read listed companies", exception); }
    }

    @Override public FinanceDashboard financeDashboard(CompanyId companyId, Instant now, ZoneId zone) {
        FinancialSnapshot allTime=snapshot(companyId).orElseThrow(()->new IllegalArgumentException("company cash account does not exist"));
        ZonedDateTime start=YearMonth.from(now.atZone(zone)).atDay(1).atStartOfDay(zone); Instant end=start.plusMonths(1).toInstant();
        long income,expense; try(Connection c=dataSource.getConnection()){income=periodTotal(c,companyId,start.toInstant(),end,"INCOME");expense=periodTotal(c,companyId,start.toInstant(),end,"EXPENSE");}catch(SQLException e){throw new IllegalStateException("could not read company finance dashboard",e);}
        Instant next=nextDividend(companyId, now); FinancialSnapshot current=new FinancialSnapshot(companyId,allTime.cash(),allTime.retainedEarnings(),allTime.accumulatedLoss(),income,expense);
        return new FinanceDashboard(current,next,recentReports(companyId,6));
    }

    private Instant nextDividend(CompanyId companyId, Instant now) {
        try(Connection c=dataSource.getConnection(); PreparedStatement s=c.prepareStatement("SELECT COALESCE((SELECT dividend_at FROM dividend_runs WHERE company_id=? AND state='COMPLETED' ORDER BY dividend_at DESC LIMIT 1), (SELECT listed_at FROM stock_listings WHERE company_id=?))")){s.setString(1,companyId.value().toString());s.setString(2,companyId.value().toString());try(ResultSet r=s.executeQuery()){if(!r.next()||r.getString(1)==null)throw new IllegalArgumentException("company is not listed");Instant next=Instant.parse(r.getString(1)).plus(java.time.Duration.ofDays(15));while(!next.isAfter(now))next=next.plus(java.time.Duration.ofDays(15));return next;}}catch(SQLException e){throw new IllegalStateException("could not read next dividend",e);}
    }

    private static long periodTotal(Connection connection, CompanyId companyId, Instant start, Instant end, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(amount_minor), 0) FROM company_operating_events WHERE company_id = ? AND kind = ? AND occurred_at >= ? AND occurred_at < ?")) {
            statement.setString(1, companyId.value().toString()); statement.setString(2, kind); statement.setString(3, start.toString()); statement.setString(4, end.toString()); try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
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
