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

    private static StockListing listing(ResultSet rows) throws SQLException {
        return new StockListing(new CompanyId(java.util.UUID.fromString(rows.getString("company_id"))), rows.getString("stock_code"),
                Money.ofMinor(rows.getLong("issue_reference_price_minor")), rows.getLong("issued_shares"), Instant.parse(rows.getString("listed_at")));
    }
}
