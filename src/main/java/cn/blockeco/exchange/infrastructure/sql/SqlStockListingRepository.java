package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.StockListing;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.StockListingRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

public final class SqlStockListingRepository implements StockListingRepository {
    private final DataSource dataSource;

    public SqlStockListingRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<StockListing> findByCompany(CompanyId companyId) {
        return find("SELECT * FROM stock_listings WHERE company_id=?", companyId.value().toString());
    }

    @Override
    public Optional<StockListing> findByCode(String stockCode) {
        return find("SELECT * FROM stock_listings WHERE stock_code=?", stockCode);
    }

    @Override
    public StockListing allocate(Connection connection, CompanyId companyId, Money issueReferencePrice, long issuedShares, Instant listedAt)
            throws SQLException {
        Optional<StockListing> existing = find(connection, "SELECT * FROM stock_listings WHERE company_id=?", companyId.value().toString());
        if (existing.isPresent()) {
            return existing.get();
        }
        try (PreparedStatement increment = connection.prepareStatement(
                "UPDATE stock_code_sequence SET last_value=last_value+1 WHERE singleton=1 AND last_value<999999")) {
            if (increment.executeUpdate() == 0) {
                throw new StockCodeExhaustedException();
            }
        }
        long sequence = value(connection);
        StockListing listing = new StockListing(companyId, String.format("BS%06d", sequence), issueReferencePrice, issuedShares, listedAt);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) {
            insert.setString(1, companyId.value().toString());
            insert.setString(2, listing.stockCode());
            insert.setLong(3, issueReferencePrice.minorUnits());
            insert.setLong(4, issuedShares);
            insert.setString(5, listedAt.toString());
            insert.executeUpdate();
        }
        return listing;
    }

    @Override
    public StockListing allocateFixed(Connection connection, CompanyId companyId, String stockCode, Money issueReferencePrice, long issuedShares, Instant listedAt)
            throws SQLException {
        Optional<StockListing> existing = find(connection, "SELECT * FROM stock_listings WHERE company_id=?", companyId.value().toString());
        if (existing.isPresent()) {
            if (!existing.get().stockCode().equals(stockCode)) throw new IllegalStateException("company already has a different stock code");
            return existing.get();
        }
        StockListing listing = new StockListing(companyId, stockCode, issueReferencePrice, issuedShares, listedAt);
        if (find(connection, "SELECT * FROM stock_listings WHERE stock_code=?", stockCode).isPresent()) {
            throw new IllegalStateException("reserved stock ticker is already in use: " + stockCode);
        }
        insert(connection, listing);
        return listing;
    }

    @Override
    public void reconcileLegacyBluechipTicker(Connection connection, CompanyId companyId, String configuredTicker) throws SQLException {
        Optional<StockListing> current = find(connection, "SELECT * FROM stock_listings WHERE company_id=?", companyId.value().toString());
        if (current.isEmpty() || current.get().stockCode().equals(configuredTicker)) return;
        String legacyCode = current.get().stockCode();
        if (!legacyCode.matches("BS[0-9]{6}") || Long.parseLong(legacyCode.substring(2)) == 0) {
            throw new IllegalStateException("bluechip ticker collision for " + configuredTicker + ": persisted code is not a legacy BS code");
        }
        if (find(connection, "SELECT * FROM stock_listings WHERE stock_code=?", configuredTicker).isPresent()) {
            throw new IllegalStateException("bluechip ticker collision for " + configuredTicker + ": configured ticker is already in use");
        }
        if (hasDependentTradesOrOrders(connection, companyId)) {
            throw new IllegalStateException("bluechip ticker migration requires manual handling because orders or trades exist for " + legacyCode);
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE stock_listings SET stock_code=? WHERE company_id=? AND stock_code=?")) {
            update.setString(1, configuredTicker); update.setString(2, companyId.value().toString()); update.setString(3, legacyCode);
            if (update.executeUpdate() != 1) throw new IllegalStateException("bluechip ticker migration state conflict");
        }
        try (PreparedStatement updateAudit = connection.prepareStatement("UPDATE audit_events SET payload_json=REPLACE(payload_json, ?, ?) WHERE company_id=? AND payload_json LIKE ?")) {
            updateAudit.setString(1, legacyCode); updateAudit.setString(2, configuredTicker); updateAudit.setString(3, companyId.value().toString()); updateAudit.setString(4, "%" + legacyCode + "%"); updateAudit.executeUpdate();
        }
    }

    @Override
    public void reconcileLegacyBluechipIssuedShares(Connection connection, CompanyId companyId, long issuedShares) throws SQLException {
        if (issuedShares <= 0) throw new IllegalArgumentException("issued shares must be positive");
        Optional<StockListing> current = find(connection, "SELECT * FROM stock_listings WHERE company_id=?", companyId.value().toString());
        if (current.isEmpty() || current.get().issuedShares() == issuedShares) return;
        if (hasDependentTradesOrOrders(connection, companyId)) {
            throw new IllegalStateException("bluechip issued-share migration requires manual handling because orders or trades exist for " + current.get().stockCode());
        }
        if (holdingTotal(connection, companyId) != issuedShares) {
            throw new IllegalStateException("bluechip issued-share migration requires manual handling because holdings do not match configured liquidity");
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE stock_listings SET issued_shares=? WHERE company_id=? AND issued_shares=?")) {
            update.setLong(1, issuedShares); update.setString(2, companyId.value().toString()); update.setLong(3, current.get().issuedShares());
            if (update.executeUpdate() != 1) throw new IllegalStateException("bluechip issued-share migration state conflict");
        }
    }

    private Optional<StockListing> find(String sql, String value) {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, sql, value);
        } catch (SQLException exception) {
            throw new IllegalStateException("could not find stock listing", exception);
        }
    }

    private static Optional<StockListing> find(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(listing(rows)) : Optional.empty();
            }
        }
    }

    private static long value(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT last_value FROM stock_code_sequence WHERE singleton=1");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IllegalStateException("stock code sequence missing");
            }
            return rows.getLong(1);
        }
    }

    private static void insert(Connection connection, StockListing listing) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) {
            insert.setString(1, listing.companyId().value().toString()); insert.setString(2, listing.stockCode());
            insert.setLong(3, listing.issueReferencePrice().minorUnits()); insert.setLong(4, listing.issuedShares()); insert.setString(5, listing.listedAt().toString()); insert.executeUpdate();
        }
    }

    private static boolean hasDependentTradesOrOrders(Connection connection, CompanyId companyId) throws SQLException {
        for (String table : new String[] {"stock_orders", "stock_trades"}) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE company_id=? LIMIT 1")) {
                statement.setString(1, companyId.value().toString());
                try (ResultSet rows = statement.executeQuery()) { if (rows.next()) return true; }
            }
        }
        return false;
    }

    private static long holdingTotal(Connection connection, CompanyId companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(available_shares + reserved_shares), 0) FROM share_holdings WHERE company_id=?")) {
            statement.setString(1, companyId.value().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("could not read bluechip share holdings");
                return rows.getLong(1);
            }
        }
    }

    private static StockListing listing(ResultSet rows) throws SQLException {
        return new StockListing(new CompanyId(java.util.UUID.fromString(rows.getString("company_id"))), rows.getString("stock_code"),
                Money.ofMinor(rows.getLong("issue_reference_price_minor")), rows.getLong("issued_shares"), Instant.parse(rows.getString("listed_at")));
    }
}
