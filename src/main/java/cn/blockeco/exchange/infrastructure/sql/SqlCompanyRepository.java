package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.CompanyRepository;
import cn.blockeco.exchange.ports.DuplicateCompanyNameException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class SqlCompanyRepository implements CompanyRepository {

    private static final String INSERT = """
            INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor,
                                   total_shares, dividend_basis_points, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT = """
            SELECT id, normalized_name, display_name, founder_uuid, status, treasury_minor,
                   total_shares, dividend_basis_points, created_at
            FROM companies
            """;
    private final DataSource dataSource;

    public SqlCompanyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void insert(Connection connection, Company company) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, company.id().value().toString());
            statement.setString(2, company.normalizedName());
            statement.setString(3, company.displayName());
            statement.setString(4, company.founderId().toString());
            statement.setString(5, company.status().name());
            statement.setLong(6, company.treasury().minorUnits());
            statement.setLong(7, company.totalShares());
            statement.setInt(8, company.dividendRate().basisPoints());
            statement.setString(9, company.createdAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isUniqueConstraint(exception)) {
                throw new DuplicateCompanyNameException(company.normalizedName(), exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<Company> findById(CompanyId id) {
        return findOne(SELECT + "WHERE id = ?", id.value().toString());
    }

    @Override
    public Optional<Company> findByNormalizedName(String normalizedName) {
        return findOne(SELECT + "WHERE normalized_name = ?", normalizedName);
    }

    private Optional<Company> findOne(String sql, String value) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapCompany(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("could not read company", exception);
        }
    }

    private static Company mapCompany(ResultSet row) throws SQLException {
        CompanyId id = new CompanyId(UUID.fromString(row.getString("id")));
        String displayName = row.getString("display_name");
        UUID founderId = UUID.fromString(row.getString("founder_uuid"));
        Money treasury = Money.ofMinor(row.getLong("treasury_minor"));
        DividendRate dividendRate = rateFromBasisPoints(row.getInt("dividend_basis_points"));
        Instant createdAt = Instant.parse(row.getString("created_at"));
        return Company.rehydrate(
                id,
                displayName,
                row.getString("normalized_name"),
                founderId,
                treasury,
                row.getLong("total_shares"),
                dividendRate,
                CompanyStatus.valueOf(row.getString("status")),
                createdAt);
    }

    private static DividendRate rateFromBasisPoints(int basisPoints) {
        return switch (basisPoints) {
            case 3000 -> DividendRate.THIRTY;
            case 5000 -> DividendRate.FIFTY;
            case 7000 -> DividendRate.SEVENTY;
            default -> throw new IllegalStateException("unknown dividend basis points: " + basisPoints);
        };
    }

    private static boolean isUniqueConstraint(SQLException exception) {
        return exception.getMessage() != null && exception.getMessage().contains("companies.normalized_name");
    }
}
