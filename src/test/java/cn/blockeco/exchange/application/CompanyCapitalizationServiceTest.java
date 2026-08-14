package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAuditLog;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyFinanceRepository;
import cn.blockeco.exchange.ports.CompanyFinanceRepository;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.TreasuryEscrowGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CompanyCapitalizationServiceTest {
    @Test
    void capitalizes_only_after_player_withdrawal_and_escrow_deposit_succeed() throws Exception {
        Path file = Files.createTempFile("blockstock-capitalization-", ".db");
        try (Database database = migrated(file)) {
            RecordingEscrow escrow = new RecordingEscrow();
            Company company = company();
            insertCompany(database, company);
            CompanyCapitalizationService service = service(database, escrow);

            service.capitalize(company, company.founderId(), Money.ofMinor(12_345), UUID.randomUUID()).toCompletableFuture().join();

            assertThat(escrow.playerWithdrawals).isEqualTo(1);
            assertThat(escrow.escrowDeposits).isEqualTo(1);
            assertThat(escrow.refunds).isZero();
            assertThat(longValue(database.dataSource().getConnection(), "SELECT cash_minor FROM company_cash_accounts")).isEqualTo(12_345);
            assertThat(longValue(database.dataSource().getConnection(), "SELECT available_shares FROM share_holdings")).isEqualTo(1_000);
            assertThat(stringValue(database.dataSource().getConnection(), "SELECT state FROM treasury_operations")).isEqualTo("COMPLETED");
            assertThat(longValue(database.dataSource().getConnection(), "SELECT COUNT(*) FROM audit_events WHERE event_type LIKE 'COMPANY_CAPITALIZATION_%'"))
                    .isGreaterThanOrEqualTo(4);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void database_failure_after_escrow_deposit_refunds_founder_once_and_marks_recovery_when_refund_is_ambiguous() throws Exception {
        Path file = Files.createTempFile("blockstock-capitalization-refund-", ".db");
        try (Database database = migrated(file)) {
            RecordingEscrow escrow = new RecordingEscrow();
            escrow.refund = EconomyGateway.Result.providerFailure("timeout");
            CompanyCapitalizationService service = new CompanyCapitalizationService(
                    new FailingFinanceRepository(database.dataSource()), new SqlAuditLog(), database, escrow, directMain(), Runnable::run, () -> Instant.parse("2026-08-14T12:00:00Z"));

            Company company = company(); insertCompany(database, company);
            service.capitalize(company, company.founderId(), Money.ofMinor(99), UUID.randomUUID()).toCompletableFuture().join();

            assertThat(escrow.playerWithdrawals).isEqualTo(1);
            assertThat(escrow.escrowDeposits).isEqualTo(1);
            assertThat(escrow.refunds).isEqualTo(1);
            assertThat(stringValue(database.dataSource().getConnection(), "SELECT state FROM treasury_operations")).isEqualTo("AMBIGUOUS");
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void legacy_company_capitalization_is_idempotent() throws Exception {
        Path file = Files.createTempFile("blockstock-capitalization-legacy-", ".db");
        try (Database database = migrated(file)) {
            Company company = company();
            insertCompany(database, company);
            RecordingEscrow escrow = new RecordingEscrow();
            CompanyCapitalizationService service = service(database, escrow);

            service.recoverPendingCapitalizations().toCompletableFuture().join();
            service.recoverPendingCapitalizations().toCompletableFuture().join();

            assertThat(escrow.playerWithdrawals).isZero();
            assertThat(longValue(database.dataSource().getConnection(), "SELECT COUNT(*) FROM company_cash_accounts")).isEqualTo(1);
            assertThat(longValue(database.dataSource().getConnection(), "SELECT COUNT(*) FROM treasury_operations")).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void recovery_marks_crash_ambiguous_prepared_operation_without_repeating_a_player_withdrawal() throws Exception {
        Path file = Files.createTempFile("blockstock-capitalization-ambiguous-", ".db");
        try (Database database = migrated(file)) {
            Company company = company(); insertCompany(database, company);
            RecordingEscrow escrow = new RecordingEscrow();
            CompanyCapitalizationService service = service(database, escrow);
            UUID operationId = UUID.randomUUID();
            database.inTransaction(c -> { new SqlCompanyFinanceRepository(database.dataSource()).prepare(c,
                    new cn.blockeco.exchange.domain.finance.TreasuryOperation(operationId, company.id(), company.founderId(), Money.ofMinor(7), operationId.toString(), cn.blockeco.exchange.domain.finance.TreasuryOperationState.PREPARED, Instant.parse("2026-08-14T12:00:00Z"), Instant.parse("2026-08-14T12:00:00Z")),
                    new cn.blockeco.exchange.domain.audit.AuditEvent(UUID.randomUUID(), java.util.Optional.of(company.id()), java.util.Optional.of(company.founderId()), "COMPANY_CAPITALIZATION_PREPARED", java.util.Map.of("operationId", operationId.toString()), Instant.parse("2026-08-14T12:00:00Z"))); return null; });

            service.recoverPendingCapitalizations().toCompletableFuture().join();

            assertThat(escrow.playerWithdrawals).isZero();
            assertThat(escrow.escrowDeposits).isZero();
            assertThat(stringValue(database.dataSource().getConnection(), "SELECT state FROM treasury_operations WHERE id = '" + operationId + "'"))
                    .isEqualTo("AMBIGUOUS");
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void recovery_marks_player_withdrawn_operation_ambiguous_without_repeating_an_escrow_deposit() throws Exception {
        Path file = Files.createTempFile("blockstock-capitalization-withdrawn-", ".db");
        try (Database database = migrated(file)) {
            Company company = company(); insertCompany(database, company);
            RecordingEscrow escrow = new RecordingEscrow(); CompanyCapitalizationService service = service(database, escrow);
            UUID operationId = UUID.randomUUID(); Instant now = Instant.parse("2026-08-14T12:00:00Z");
            database.inTransaction(c -> { SqlCompanyFinanceRepository repository = new SqlCompanyFinanceRepository(database.dataSource());
                var operation = new cn.blockeco.exchange.domain.finance.TreasuryOperation(operationId, company.id(), company.founderId(), Money.ofMinor(7), operationId.toString(), cn.blockeco.exchange.domain.finance.TreasuryOperationState.PREPARED, now, now);
                repository.prepare(c, operation, new cn.blockeco.exchange.domain.audit.AuditEvent(UUID.randomUUID(), java.util.Optional.of(company.id()), java.util.Optional.of(company.founderId()), "COMPANY_CAPITALIZATION_PREPARED", java.util.Map.of("operationId", operationId.toString()), now));
                repository.transition(c, operationId, cn.blockeco.exchange.domain.finance.TreasuryOperationState.PREPARED, cn.blockeco.exchange.domain.finance.TreasuryOperationState.PLAYER_WITHDRAWN, new cn.blockeco.exchange.domain.audit.AuditEvent(UUID.randomUUID(), java.util.Optional.of(company.id()), java.util.Optional.of(company.founderId()), "COMPANY_CAPITALIZATION_PLAYER_WITHDRAWN", java.util.Map.of("operationId", operationId.toString()), now)); return null; });

            service.recoverPendingCapitalizations().toCompletableFuture().join();

            assertThat(escrow.playerWithdrawals).isZero();
            assertThat(escrow.escrowDeposits).isZero();
            assertThat(stringValue(database.dataSource().getConnection(), "SELECT state FROM treasury_operations WHERE id = '" + operationId + "'"))
                    .isEqualTo("AMBIGUOUS");
        } finally { Files.deleteIfExists(file); }
    }

    private static CompanyCapitalizationService service(Database database, RecordingEscrow escrow) {
        return new CompanyCapitalizationService(new SqlCompanyFinanceRepository(database.dataSource()), new SqlAuditLog(), database, escrow, directMain(), Runnable::run, () -> Instant.parse("2026-08-14T12:00:00Z"));
    }
    private static MainThreadExecutor directMain() { return new MainThreadExecutor() { @Override public <T> CompletionStage<T> submit(Supplier<T> work) { return CompletableFuture.completedFuture(work.get()); } }; }
    private static Database migrated(Path file) throws Exception { Database database = new Database("jdbc:sqlite:" + file); database.migrate(); return database; }
    private static Company company() { return Company.register(new CompanyId(UUID.randomUUID()), "Capital Guild", UUID.randomUUID(), Money.ofMinor(500), DividendRate.FIFTY, Instant.parse("2026-08-14T12:00:00Z")); }
    private static void insertCompany(Database database, Company company) { database.inTransaction(c -> { try (PreparedStatement s = c.prepareStatement("INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor, total_shares, dividend_basis_points, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { s.setString(1, company.id().value().toString()); s.setString(2, company.normalizedName()); s.setString(3, company.displayName()); s.setString(4, company.founderId().toString()); s.setString(5, company.status().name()); s.setLong(6, company.treasury().minorUnits()); s.setLong(7, company.totalShares()); s.setInt(8, company.dividendRate().basisPoints()); s.setString(9, company.createdAt().toString()); s.executeUpdate(); } return null; }); }
    private static long longValue(Connection c, String sql) throws Exception { try (c; PreparedStatement s = c.prepareStatement(sql); var rows = s.executeQuery()) { rows.next(); return rows.getLong(1); } }
    private static String stringValue(Connection c, String sql) throws Exception { try (c; PreparedStatement s = c.prepareStatement(sql); var rows = s.executeQuery()) { rows.next(); return rows.getString(1); } }

    private static final class RecordingEscrow implements TreasuryEscrowGateway {
        int playerWithdrawals; int escrowDeposits; int refunds; EconomyGateway.Result refund = EconomyGateway.Result.success("");
        @Override public EconomyGateway.Result withdrawPlayer(UUID playerId, Money amount, UUID operationId) { playerWithdrawals++; return EconomyGateway.Result.success(""); }
        @Override public EconomyGateway.Result depositEscrow(Money amount, UUID operationId) { escrowDeposits++; return EconomyGateway.Result.success(""); }
        @Override public EconomyGateway.Result withdrawEscrow(Money amount, UUID operationId) { return EconomyGateway.Result.success(""); }
        @Override public EconomyGateway.Result refundPlayer(UUID playerId, Money amount, UUID operationId) { refunds++; return refund; }
    }
    private static final class FailingFinanceRepository implements CompanyFinanceRepository {
        private final SqlCompanyFinanceRepository delegate;
        FailingFinanceRepository(javax.sql.DataSource source) { delegate = new SqlCompanyFinanceRepository(source); }
        @Override public void createCapitalization(Connection connection, cn.blockeco.exchange.domain.finance.CompanyCashAccount cash, cn.blockeco.exchange.domain.finance.ShareHolding holding, cn.blockeco.exchange.domain.finance.TreasuryOperation operation, cn.blockeco.exchange.domain.audit.AuditEvent audit) { throw new IllegalStateException("database offline"); }
        @Override public void prepare(Connection connection, cn.blockeco.exchange.domain.finance.TreasuryOperation operation, cn.blockeco.exchange.domain.audit.AuditEvent audit) throws java.sql.SQLException { delegate.prepare(connection, operation, audit); }
        @Override public void transition(Connection connection, UUID id, cn.blockeco.exchange.domain.finance.TreasuryOperationState expected, cn.blockeco.exchange.domain.finance.TreasuryOperationState state, cn.blockeco.exchange.domain.audit.AuditEvent audit) throws java.sql.SQLException { delegate.transition(connection, id, expected, state, audit); }
        @Override public java.util.Optional<cn.blockeco.exchange.domain.finance.TreasuryOperation> findById(UUID id) { return delegate.findById(id); }
        @Override public java.util.List<cn.blockeco.exchange.domain.finance.TreasuryOperation> findUnsettledOperations() { return delegate.findUnsettledOperations(); }
        @Override public java.util.List<CapitalizationRecoveryRecord> findAmbiguousCapitalizations() { return delegate.findAmbiguousCapitalizations(); }
        @Override public java.util.List<Company> findLegacyCompaniesWithoutFinance() { return delegate.findLegacyCompaniesWithoutFinance(); }
    }
}
