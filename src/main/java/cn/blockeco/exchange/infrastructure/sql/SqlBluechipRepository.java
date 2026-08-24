package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.BluechipRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class SqlBluechipRepository implements BluechipRepository {
    private static final String SELECT = """
            SELECT bc.company_id, bc.industry, bc.system_account_uuid, bc.model_price_minor, bc.lower_price_minor,
                   bc.upper_price_minor, sl.stock_code, sl.issue_reference_price_minor, sl.issued_shares, sl.listed_at,
                   h.available_shares, COALESCE((SELECT SUM(cash_delta_minor) FROM bluechip_fund_audit fa
                       WHERE fa.company_id = bc.company_id), 0) AS fund_cash_minor
            FROM bluechip_companies bc
            JOIN stock_listings sl ON sl.company_id = bc.company_id
            JOIN share_holdings h ON h.company_id = bc.company_id AND h.holder_uuid = bc.system_account_uuid
            """;
    private final DataSource dataSource;

    public SqlBluechipRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override public Optional<BluechipCompany> findByStockCode(String stockCode) { return findOne(SELECT + " WHERE sl.stock_code = ?", stockCode); }
    @Override public Optional<BluechipCompany> findByCompanyId(CompanyId companyId) { return findOne(SELECT + " WHERE bc.company_id = ?", companyId.value().toString()); }
    @Override public List<BluechipCompany> all() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(SELECT + " ORDER BY sl.stock_code"); ResultSet rows = statement.executeQuery()) {
            java.util.ArrayList<BluechipCompany> companies = new java.util.ArrayList<>(); while (rows.next()) companies.add(map(rows)); return List.copyOf(companies);
        } catch (SQLException exception) { throw new IllegalStateException("could not read bluechip companies", exception); }
    }

    @Override public void insertInitial(Connection connection, BluechipSeed seed) throws SQLException {
        requireTransaction(connection);
        insertHolding(connection, seed);
        creditSystemCash(connection, seed.systemAccountId(), seed.fundCash());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bluechip_companies (company_id, industry, system_account_uuid, lower_price_minor, upper_price_minor,
                  model_price_minor, spread_bps, event_sensitivity_bps, payout_bps, next_event_at, next_dividend_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, seed.companyId().value().toString()); statement.setString(2, seed.industry()); statement.setString(3, seed.systemAccountId().toString());
            statement.setLong(4, seed.lowerPrice().minorUnits()); statement.setLong(5, seed.upperPrice().minorUnits()); statement.setLong(6, seed.referencePrice().minorUnits());
            statement.setInt(7, seed.spreadBps()); statement.setInt(8, seed.eventSensitivityBps()); statement.setInt(9, seed.payoutBps());
            statement.setString(10, seed.initializedAt().plus(6, ChronoUnit.HOURS).toString()); statement.setString(11, seed.initializedAt().plus(15, ChronoUnit.DAYS).toString()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO company_industry (company_id, industry) VALUES (?, ?)")) {
            statement.setString(1, seed.companyId().value().toString()); statement.setString(2, seed.industry()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO bluechip_fund_audit (id, company_id, operation, cash_delta_minor, shares_delta, occurred_at) VALUES (?, ?, 'BLUECHIP_INITIALIZED', ?, ?, ?)")) {
            statement.setString(1, deterministicId("fund-audit:", seed.companyId()).toString()); statement.setString(2, seed.companyId().value().toString());
            statement.setLong(3, seed.fundCash().minorUnits()); statement.setLong(4, seed.fundShares()); statement.setString(5, seed.initializedAt().toString()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at) VALUES (?, ?, ?, 'BLUECHIP_INITIALIZED', ?, ?)")) {
            statement.setString(1, deterministicId("audit:", seed.companyId()).toString()); statement.setString(2, seed.companyId().value().toString()); statement.setString(3, seed.systemAccountId().toString());
            statement.setString(4, "{\"fundCashMinor\":" + seed.fundCash().minorUnits() + ",\"fundShares\":" + seed.fundShares() + ",\"stockCode\":\"" + seed.listing().stockCode() + "\"}"); statement.setString(5, seed.initializedAt().toString()); statement.executeUpdate();
        }
    }

    private Optional<BluechipCompany> findOne(String sql, String value) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(map(rows)) : Optional.empty(); }
        } catch (SQLException exception) { throw new IllegalStateException("could not read bluechip company", exception); }
    }
    private static BluechipCompany map(ResultSet row) throws SQLException {
        CompanyId companyId = new CompanyId(UUID.fromString(row.getString("company_id")));
        StockListing listing = new StockListing(companyId, row.getString("stock_code"), Money.ofMinor(row.getLong("issue_reference_price_minor")), row.getLong("issued_shares"), Instant.parse(row.getString("listed_at")));
        return new BluechipCompany(companyId, listing, row.getString("industry"), UUID.fromString(row.getString("system_account_uuid")), Money.ofMinor(row.getLong("model_price_minor")), Money.ofMinor(row.getLong("lower_price_minor")), Money.ofMinor(row.getLong("upper_price_minor")), row.getLong("available_shares"), Money.ofMinor(row.getLong("fund_cash_minor")));
    }
    private static void insertHolding(Connection connection, BluechipSeed seed) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO share_holdings (company_id, holder_uuid, available_shares, reserved_shares) VALUES (?, ?, ?, 0)")) {
            statement.setString(1, seed.companyId().value().toString()); statement.setString(2, seed.systemAccountId().toString()); statement.setLong(3, seed.fundShares()); statement.executeUpdate();
        }
    }
    private static void creditSystemCash(Connection connection, UUID accountId, Money amount) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT available_minor, reserved_minor FROM securities_cash_accounts WHERE player_uuid = ?")) {
            select.setString(1, accountId.toString()); try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) { try (PreparedStatement insert = connection.prepareStatement("INSERT INTO securities_cash_accounts (player_uuid, available_minor, reserved_minor) VALUES (?, ?, 0)")) { insert.setString(1, accountId.toString()); insert.setLong(2, amount.minorUnits()); insert.executeUpdate(); } return; }
                long available = Math.addExact(rows.getLong(1), amount.minorUnits()); long reserved = rows.getLong(2);
                try (PreparedStatement update = connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = ? WHERE player_uuid = ? AND available_minor = ? AND reserved_minor = ?")) {
                    update.setLong(1, available); update.setString(2, accountId.toString()); update.setLong(3, rows.getLong(1)); update.setLong(4, reserved); if (update.executeUpdate() != 1) throw new IllegalStateException("system cash account changed during bootstrap");
                }
            }
        }
    }
    private static UUID deterministicId(String prefix, CompanyId companyId) { return UUID.nameUUIDFromBytes((prefix + companyId.value()).getBytes(StandardCharsets.UTF_8)); }
    private static void requireTransaction(Connection connection) throws SQLException { if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required"); }
}
