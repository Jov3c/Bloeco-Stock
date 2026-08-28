package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.domain.finance.OperatingEventKind;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import cn.blockeco.exchange.ports.CompanyOperationsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlCompanyOperationsRepositoryTest {
    private static final Instant RECORDED_AT = Instant.parse("2026-08-28T12:00:00Z");

    @Test
    void recordsIncomeOnceAndReconcilesCompanyTreasury() throws Exception {
        try (Fixture fixture = Fixture.create(100)) {
            CompanyOperationsRepository repository = new SqlCompanyOperationsRepository(fixture.database.dataSource());
            VerifiedOperatingEvent income = new VerifiedOperatingEvent("shop", "sale-1", OperatingEventKind.INCOME, 40,
                    RECORDED_AT.minusSeconds(30), "已完成销售");

            CompanyOperationsRepository.RecordResult first = fixture.database.inTransaction(connection -> repository.record(connection, fixture.binding, income, RECORDED_AT));
            assertThat(first)
                    .isEqualTo(CompanyOperationsRepository.RecordResult.RECORDED);
            long eventsAfterFirst = fixture.count("company_operating_events");
            long auditsAfterFirst = fixture.count("audit_events");
            long ledgerAfterFirst = fixture.countCompanyTreasuryEntries();

            CompanyOperationsRepository.RecordResult duplicate = fixture.database.inTransaction(connection -> repository.record(connection, fixture.binding, income, RECORDED_AT));
            assertThat(duplicate)
                    .isEqualTo(CompanyOperationsRepository.RecordResult.DUPLICATE);

            assertThat(fixture.snapshot(repository)).isEqualTo(new CompanyOperationsRepository.FinancialSnapshot(
                    fixture.companyId, 140, 40, 0, 40, 0));
            assertThat(fixture.count("company_operating_events")).isEqualTo(eventsAfterFirst);
            assertThat(fixture.count("audit_events")).isEqualTo(auditsAfterFirst);
            assertThat(fixture.countCompanyTreasuryEntries()).isEqualTo(ledgerAfterFirst);
            assertThat(fixture.companyTreasuryDelta()).isEqualTo(140);
        }
    }

    @Test
    void expenseConsumesEarningsThenCreatesAccumulatedLoss() throws Exception {
        try (Fixture fixture = Fixture.create(100)) {
            CompanyOperationsRepository repository = new SqlCompanyOperationsRepository(fixture.database.dataSource());
            fixture.database.inTransaction(connection -> repository.record(connection, fixture.binding,
                    new VerifiedOperatingEvent("shop", "sale-1", OperatingEventKind.INCOME, 40, RECORDED_AT, "销售"), RECORDED_AT));

            CompanyOperationsRepository.RecordResult expense = fixture.database.inTransaction(connection -> repository.record(connection, fixture.binding,
                    new VerifiedOperatingEvent("shop", "cost-1", OperatingEventKind.EXPENSE, 65, RECORDED_AT.plusSeconds(1), "成本"), RECORDED_AT.plusSeconds(1)));
            assertThat(expense)
                    .isEqualTo(CompanyOperationsRepository.RecordResult.RECORDED);

            assertThat(fixture.snapshot(repository)).isEqualTo(new CompanyOperationsRepository.FinancialSnapshot(
                    fixture.companyId, 75, 0, 25, 40, 65));
            assertThat(fixture.companyTreasuryDelta()).isEqualTo(75);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path file;
        private final Database database;
        private final CompanyId companyId;
        private final AssetBinding binding;

        private Fixture(Path file, Database database, CompanyId companyId, AssetBinding binding) {
            this.file = file;
            this.database = database;
            this.companyId = companyId;
            this.binding = binding;
        }

        static Fixture create(long cash) throws Exception {
            Path file = Files.createTempFile("blockeco-company-operations-", ".db");
            Database database = new Database("jdbc:sqlite:" + file);
            database.migrate();
            CompanyId companyId = new CompanyId(UUID.randomUUID());
            AssetBinding binding = new AssetBinding(UUID.randomUUID(), companyId, "shop", "shop-1", UUID.randomUUID(),
                    AssetBindingState.ACTIVE, RECORDED_AT.minusSeconds(60));
            database.inTransaction(connection -> {
                try (PreparedStatement company = connection.prepareStatement("INSERT INTO companies VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                     PreparedStatement account = connection.prepareStatement("INSERT INTO company_cash_accounts (company_id, cash_minor, paid_in_capital_minor, retained_earnings_minor, reserved_minor) VALUES (?, ?, ?, 0, 0)");
                     PreparedStatement asset = connection.prepareStatement("INSERT INTO asset_bindings VALUES (?, ?, ?, ?, ?, ?, ?)");
                     PreparedStatement ledger = connection.prepareStatement("INSERT INTO escrow_ledger_entries (id, liability_kind, company_id, player_uuid, amount_minor, operation_id, trade_id, occurred_at) VALUES (?, 'COMPANY_TREASURY', ?, NULL, ?, NULL, NULL, ?)")) {
                    company.setString(1, companyId.value().toString()); company.setString(2, "test-company"); company.setString(3, "Test Company");
                    company.setString(4, UUID.randomUUID().toString()); company.setString(5, "LISTED"); company.setLong(6, 0); company.setLong(7, 1000); company.setInt(8, 5000); company.setString(9, RECORDED_AT.toString()); company.setInt(10, 0); company.executeUpdate();
                    account.setString(1, companyId.value().toString()); account.setLong(2, cash); account.setLong(3, cash); account.executeUpdate();
                    asset.setString(1, binding.id().toString()); asset.setString(2, companyId.value().toString()); asset.setString(3, binding.adapterId()); asset.setString(4, binding.externalKey()); asset.setString(5, binding.verifiedOwner().toString()); asset.setString(6, binding.state().name()); asset.setString(7, binding.createdAt().toString()); asset.executeUpdate();
                    ledger.setString(1, UUID.randomUUID().toString()); ledger.setString(2, companyId.value().toString()); ledger.setLong(3, cash); ledger.setString(4, RECORDED_AT.toString()); ledger.executeUpdate();
                }
                return null;
            });
            return new Fixture(file, database, companyId, binding);
        }

        CompanyOperationsRepository.FinancialSnapshot snapshot(CompanyOperationsRepository repository) {
            return repository.snapshot(companyId).orElseThrow();
        }

        long count(String table) { return value("SELECT COUNT(*) FROM " + table); }
        long countCompanyTreasuryEntries() { return value("SELECT COUNT(*) FROM escrow_ledger_entries WHERE liability_kind = 'COMPANY_TREASURY'"); }
        long companyTreasuryDelta() { return value("SELECT SUM(amount_minor) FROM escrow_ledger_entries WHERE liability_kind = 'COMPANY_TREASURY'"); }

        private long value(String sql) {
            try (Connection connection = database.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
                rows.next(); return rows.getLong(1);
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }

        @Override public void close() throws Exception { database.close(); Files.deleteIfExists(file); }
    }
}
