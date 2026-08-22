package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.application.CapitalizationRecoveryRecord;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.finance.CompanyCashAccount;
import cn.blockeco.exchange.domain.finance.ShareHolding;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.finance.TreasuryOperationState;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.CompanyFinanceRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class SqlCompanyFinanceRepository implements CompanyFinanceRepository {
    private final DataSource dataSource;
    private final SqlAuditLog audits = new SqlAuditLog();
    public SqlCompanyFinanceRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override public void prepare(Connection c, TreasuryOperation operation, AuditEvent audit) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO treasury_operations (id, company_id, player_uuid, amount_minor, provider_correlation_key, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, operation.id().toString()); s.setString(2, operation.companyId().value().toString()); s.setString(3, operation.playerId().toString()); s.setLong(4, operation.amount().minorUnits()); s.setString(5, operation.providerCorrelationKey()); s.setString(6, operation.state().name()); s.setString(7, operation.createdAt().toString()); s.setString(8, operation.updatedAt().toString()); s.executeUpdate();
        }
        audits.append(c, audit);
    }
    @Override public void transition(Connection c, UUID id, TreasuryOperationState expected, TreasuryOperationState state, AuditEvent audit) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE treasury_operations SET state = ?, updated_at = ? WHERE id = ? AND state = ?")) {
            s.setString(1, state.name()); s.setString(2, audit.occurredAt().toString()); s.setString(3, id.toString()); s.setString(4, expected.name());
            if (s.executeUpdate() != 1) throw new IllegalStateException("treasury operation state conflict for " + id);
        }
        audits.append(c, audit);
    }
    @Override public void createCapitalization(Connection c, CompanyCashAccount cash, ShareHolding holding, TreasuryOperation operation, AuditEvent audit) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO company_cash_accounts (company_id, cash_minor, paid_in_capital_minor, retained_earnings_minor, reserved_minor) VALUES (?, ?, ?, ?, ?)")) {
            s.setString(1, cash.companyId().value().toString()); s.setLong(2, cash.cash().minorUnits()); s.setLong(3, cash.paidInCapital().minorUnits()); s.setLong(4, cash.retainedEarnings().minorUnits()); s.setLong(5, cash.reserved().minorUnits()); s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO share_holdings (company_id, holder_uuid, available_shares, reserved_shares) VALUES (?, ?, ?, ?)")) {
            s.setString(1, holding.companyId().value().toString()); s.setString(2, holding.holderId().toString()); s.setLong(3, holding.availableShares()); s.setLong(4, holding.reservedShares()); s.executeUpdate();
        }
        appendCompanyTreasuryLedger(c, cash.companyId(), cash.cash().minorUnits(), audit.occurredAt());
        transition(c, operation.id(), TreasuryOperationState.ESCROW_DEPOSITED, TreasuryOperationState.COMPLETED, audit);
    }
    @Override public Optional<TreasuryOperation> findById(UUID id) { return find("SELECT * FROM treasury_operations WHERE id = ?", id.toString()).stream().findFirst(); }
    @Override public List<TreasuryOperation> findUnsettledOperations() { return find("SELECT * FROM treasury_operations WHERE state NOT IN ('COMPLETED','REFUNDED','AMBIGUOUS')", null); }
    @Override public List<CapitalizationRecoveryRecord> findAmbiguousCapitalizations() {
        String sql = """
                SELECT t.*, COALESCE((SELECT payload_json FROM audit_events a
                  WHERE a.event_type = 'COMPANY_CAPITALIZATION_AMBIGUOUS'
                    AND a.payload_json LIKE '%\"operationId\":\"' || t.id || '\"%'
                  ORDER BY a.sequence DESC LIMIT 1), '') AS ambiguity_payload
                FROM treasury_operations t WHERE t.state = 'AMBIGUOUS' ORDER BY t.updated_at, t.id
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet rows = s.executeQuery()) {
            java.util.ArrayList<CapitalizationRecoveryRecord> results = new java.util.ArrayList<>();
            while (rows.next()) results.add(new CapitalizationRecoveryRecord(operation(rows), reason(rows.getString("ambiguity_payload"))));
            return results;
        } catch (SQLException e) { throw new IllegalStateException("could not read ambiguous capitalizations", e); }
    }
    @Override public List<Company> findLegacyCompaniesWithoutFinance() {
        String sql = "SELECT c.* FROM companies c LEFT JOIN company_cash_accounts f ON f.company_id = c.id WHERE f.company_id IS NULL";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet rows = s.executeQuery()) {
            java.util.ArrayList<Company> results = new java.util.ArrayList<>(); while (rows.next()) results.add(company(rows)); return results;
        } catch (SQLException e) { throw new IllegalStateException("could not find legacy companies", e); }
    }
    private List<TreasuryOperation> find(String sql, String value) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (value != null) s.setString(1, value);
            try (ResultSet rows = s.executeQuery()) { java.util.ArrayList<TreasuryOperation> results = new java.util.ArrayList<>(); while (rows.next()) results.add(operation(rows)); return results; }
        } catch (SQLException e) { throw new IllegalStateException("could not read treasury operations", e); }
    }
    private static TreasuryOperation operation(ResultSet r) throws SQLException { return new TreasuryOperation(UUID.fromString(r.getString("id")), new CompanyId(UUID.fromString(r.getString("company_id"))), UUID.fromString(r.getString("player_uuid")), Money.ofMinor(r.getLong("amount_minor")), r.getString("provider_correlation_key"), TreasuryOperationState.valueOf(r.getString("state")), Instant.parse(r.getString("created_at")), Instant.parse(r.getString("updated_at"))); }
    private static String reason(String payload) { java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\\"reason\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(payload); return match.find() ? match.group(1).replace("\\\\\"", "\"").replace("\\\\\\\\", "\\") : ""; }
    private static Company company(ResultSet r) throws SQLException { return Company.rehydrate(new CompanyId(UUID.fromString(r.getString("id"))), r.getString("display_name"), r.getString("normalized_name"), UUID.fromString(r.getString("founder_uuid")), Money.ofMinor(r.getLong("treasury_minor")), r.getLong("total_shares"), rate(r.getInt("dividend_basis_points")), CompanyStatus.valueOf(r.getString("status")), Instant.parse(r.getString("created_at"))); }
    private static DividendRate rate(int bps) { return switch (bps) { case 3000 -> DividendRate.THIRTY; case 5000 -> DividendRate.FIFTY; case 7000 -> DividendRate.SEVENTY; default -> throw new IllegalStateException("unknown dividend basis points: " + bps); }; }
    private static void appendCompanyTreasuryLedger(Connection c, CompanyId company, long amount, Instant at) throws SQLException {
        if (amount == 0) return;
        try (PreparedStatement s = c.prepareStatement("INSERT INTO escrow_ledger_entries (id,liability_kind,company_id,player_uuid,amount_minor,operation_id,trade_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)")) {
            s.setString(1, UUID.randomUUID().toString()); s.setString(2, "COMPANY_TREASURY"); s.setString(3, company.value().toString()); s.setNull(4, java.sql.Types.VARCHAR); s.setLong(5, amount); s.setNull(6, java.sql.Types.VARCHAR); s.setNull(7, java.sql.Types.VARCHAR); s.setString(8, at.toString()); s.executeUpdate();
        }
    }
}
