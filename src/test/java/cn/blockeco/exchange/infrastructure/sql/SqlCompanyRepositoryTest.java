package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AuditLog;
import cn.blockeco.exchange.ports.DuplicateCompanyNameException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlCompanyRepositoryTest {

    @Test
    void roundTripsEveryCompanyFieldThroughSqlite() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-companies-", ".db");
        Company company = company("Northwind Guild", 17_500, DividendRate.FIFTY);
        try (Database database = migratedDatabase(databaseFile)) {
            SqlCompanyRepository repository = new SqlCompanyRepository(database.dataSource());

            database.inTransaction(connection -> {
                repository.insert(connection, company);
                return null;
            });

            Company byId = repository.findById(company.id()).orElseThrow();
            Company byName = repository.findByNormalizedName("northwind guild").orElseThrow();
            assertCompanyMatches(byId, company);
            assertCompanyMatches(byName, company);
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void translatesDuplicateNormalizedNameIntoTypedException() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-company-duplicate-", ".db");
        try (Database database = migratedDatabase(databaseFile)) {
            SqlCompanyRepository repository = new SqlCompanyRepository(database.dataSource());
            database.inTransaction(connection -> {
                repository.insert(connection, company("Northwind Guild", 1, DividendRate.THIRTY));
                return null;
            });

            assertThatThrownBy(() -> database.inTransaction(connection -> {
                repository.insert(connection, company(" northwind   guild ", 2, DividendRate.SEVENTY));
                return null;
            })).isInstanceOf(DuplicateCompanyNameException.class);
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void readsLegallyRehydratedCompanyAfterShareIncreaseAndListing() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-company-rehydration-", ".db");
        CompanyId id = new CompanyId(UUID.fromString("cb77f738-c87e-414d-b1a1-4fbd6442cd58"));
        UUID founderId = UUID.fromString("8c760b55-95b3-49e5-982f-cb4ca336cf99");
        Instant createdAt = Instant.parse("2026-08-14T04:00:00Z");
        try (Database database = migratedDatabase(databaseFile)) {
            insertCompanyRow(
                    database,
                    id,
                    "northwind guild",
                    "Northwind Guild",
                    founderId,
                    CompanyStatus.LISTED,
                    17_500,
                    1001,
                    5000,
                    createdAt);
            SqlCompanyRepository repository = new SqlCompanyRepository(database.dataSource());

            Company actual = repository.findById(id).orElseThrow();

            assertThat(actual.id()).isEqualTo(id);
            assertThat(actual.normalizedName()).isEqualTo("northwind guild");
            assertThat(actual.displayName()).isEqualTo("Northwind Guild");
            assertThat(actual.founderId()).isEqualTo(founderId);
            assertThat(actual.status()).isEqualTo(CompanyStatus.LISTED);
            assertThat(actual.treasury()).isEqualTo(Money.ofMinor(17_500));
            assertThat(actual.totalShares()).isEqualTo(1001);
            assertThat(actual.dividendRate()).isEqualTo(DividendRate.FIFTY);
            assertThat(actual.createdAt()).isEqualTo(createdAt);
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void appendsAuditEventsWithDeterministicSimpleJsonPayload() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-audit-", ".db");
        UUID actor = UUID.fromString("d2d3dd1f-6390-4fd4-a8b9-717169dd1c13");
        UUID eventId = UUID.fromString("8b7e3904-59f6-47c2-839c-bf9f9b9c3718");
        try (Database database = migratedDatabase(databaseFile)) {
            AuditLog auditLog = new SqlAuditLog();
            AuditEvent event = new AuditEvent(
                    eventId,
                    Optional.of(new CompanyId(UUID.fromString("d680b4c9-b1d4-48b0-a835-b3dc5b38ced8"))),
                    Optional.of(actor),
                    "COMPANY_REGISTERED",
                    Map.of("zebra", true, "amount", 17500, "name", "Northwind Guild"),
                    Instant.parse("2026-08-14T04:00:00Z"));

            database.inTransaction(connection -> {
                auditLog.append(connection, event);
                return null;
            });

            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(auditJson(connection, eventId)).isEqualTo(
                        "{\"amount\":17500,\"name\":\"Northwind Guild\",\"zebra\":true}");
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void rollsBackFailedTransactionAndRestoresAutoCommit() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-rollback-", ".db");
        Company company = company("Rollback Guild", 100, DividendRate.THIRTY);
        try (Database database = migratedDatabase(databaseFile)) {
            SqlCompanyRepository repository = new SqlCompanyRepository(database.dataSource());

            assertThatThrownBy(() -> database.inTransaction(connection -> {
                repository.insert(connection, company);
                throw new IllegalArgumentException("stop");
            })).isInstanceOf(IllegalArgumentException.class);

            assertThat(repository.findById(company.id())).isEmpty();
            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(connection.getAutoCommit()).isTrue();
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void rejectsNonSimpleAuditPayloadValues() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-audit-invalid-", ".db");
        try (Database database = migratedDatabase(databaseFile)) {
            AuditLog auditLog = new SqlAuditLog();
            AuditEvent event = new AuditEvent(
                    UUID.randomUUID(), Optional.empty(), Optional.empty(), "INVALID", Map.of("nested", Map.of()), Instant.now());

            assertThatThrownBy(() -> database.inTransaction(connection -> {
                auditLog.append(connection, event);
                return null;
            })).isInstanceOf(IllegalArgumentException.class);
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    private Database migratedDatabase(Path databaseFile) throws Exception {
        Database database = new Database("jdbc:sqlite:" + databaseFile);
        database.migrate();
        return database;
    }

    private Company company(String name, long treasury, DividendRate rate) {
        return Company.register(
                new CompanyId(UUID.randomUUID()),
                name,
                UUID.randomUUID(),
                Money.ofMinor(treasury),
                rate,
                Instant.parse("2026-08-14T04:00:00Z"));
    }

    private void assertCompanyMatches(Company actual, Company expected) {
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.normalizedName()).isEqualTo(expected.normalizedName());
        assertThat(actual.displayName()).isEqualTo(expected.displayName());
        assertThat(actual.founderId()).isEqualTo(expected.founderId());
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.treasury()).isEqualTo(expected.treasury());
        assertThat(actual.totalShares()).isEqualTo(expected.totalShares());
        assertThat(actual.dividendRate()).isEqualTo(expected.dividendRate());
        assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
    }

    private void insertCompanyRow(
            Database database,
            CompanyId id,
            String normalizedName,
            String displayName,
            UUID founderId,
            CompanyStatus status,
            long treasuryMinor,
            long totalShares,
            int dividendBasisPoints,
            Instant createdAt) {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor,
                                           total_shares, dividend_basis_points, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, id.value().toString());
                statement.setString(2, normalizedName);
                statement.setString(3, displayName);
                statement.setString(4, founderId.toString());
                statement.setString(5, status.name());
                statement.setLong(6, treasuryMinor);
                statement.setLong(7, totalShares);
                statement.setInt(8, dividendBasisPoints);
                statement.setString(9, createdAt.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private String auditJson(Connection connection, UUID eventId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT payload_json FROM audit_events WHERE event_id = ?")) {
            statement.setString(1, eventId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }
}
