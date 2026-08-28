package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.domain.finance.OperatingEventKind;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyOperationsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyFinancialReportServiceTest {
    @Test
    void closesPreviousServerTimezoneMonthOnceAndPublishesOneAnnouncement() throws Exception {
        Path file = Files.createTempFile("blockeco-monthly-report-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            CompanyId company = new CompanyId(UUID.randomUUID());
            AssetBinding binding = new AssetBinding(UUID.randomUUID(), company, "shop", "shop-1", UUID.randomUUID(), AssetBindingState.ACTIVE, Instant.parse("2026-07-31T15:00:00Z"));
            seed(database, company, binding);
            seedBluechip(database);
            SqlCompanyOperationsRepository repository = new SqlCompanyOperationsRepository(database.dataSource());
            database.inTransaction(c -> { repository.record(c, binding, new VerifiedOperatingEvent("shop", "income", OperatingEventKind.INCOME, 40, Instant.parse("2026-08-31T15:59:59Z"), "销售"), Instant.parse("2026-09-01T00:01:00Z")); return null; });
            database.inTransaction(c -> { repository.record(c, binding, new VerifiedOperatingEvent("shop", "expense", OperatingEventKind.EXPENSE, 15, Instant.parse("2026-08-31T16:00:00Z"), "九月成本"), Instant.parse("2026-09-01T00:01:00Z")); return null; });

            CompanyFinancialReportService service = new CompanyFinancialReportService(repository, database, Runnable::run,
                    () -> Instant.parse("2026-09-01T00:01:00Z"), ZoneId.of("Asia/Shanghai"));
            assertThat(service.closePreviousMonth().toCompletableFuture().join()).containsExactly(company);
            assertThat(service.closePreviousMonth().toCompletableFuture().join()).isEmpty();
            assertThat(repository.recentReports(company, 6)).singleElement().satisfies(report -> {
                assertThat(report.periodStart().toString()).isEqualTo("2026-07-31T16:00:00Z");
                assertThat(report.income()).isEqualTo(40);
                assertThat(report.expense()).isZero();
                assertThat(report.netProfit()).isEqualTo(40);
            });
            assertThat(count(database, "SELECT COUNT(*) FROM company_announcements WHERE company_id = ? AND body LIKE 'MONTHLY_REPORT:%'", company)).isEqualTo(1);
            assertThat(count(database, "SELECT COUNT(*) FROM audit_events WHERE company_id = ? AND event_type = 'MONTHLY_REPORT_PUBLISHED'", company)).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void skipsListedBluechipsWithoutPlayerCashAccountsWhenClosingPlayerReports() throws Exception {
        Path file = Files.createTempFile("blockeco-monthly-bluechip-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); CompanyId player = new CompanyId(UUID.randomUUID());
            AssetBinding binding = new AssetBinding(UUID.randomUUID(), player, "shop", "shop-player", UUID.randomUUID(), AssetBindingState.ACTIVE, Instant.EPOCH);
            seed(database, player, binding); seedBluechip(database);
            SqlCompanyOperationsRepository repository = new SqlCompanyOperationsRepository(database.dataSource());
            CompanyFinancialReportService service = new CompanyFinancialReportService(repository, database, Runnable::run, () -> Instant.parse("2026-09-01T00:01:00Z"), ZoneId.of("Asia/Shanghai"));
            assertThat(service.closePreviousMonth().toCompletableFuture().join()).containsExactly(player);
            assertThat(repository.recentReports(player, 6)).hasSize(1);
        } finally { Files.deleteIfExists(file); }
    }

    private static void seed(Database database, CompanyId company, AssetBinding binding) {
        database.inTransaction(c -> {
            try (PreparedStatement companyInsert = c.prepareStatement("INSERT INTO companies VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement cash = c.prepareStatement("INSERT INTO company_cash_accounts (company_id, cash_minor, paid_in_capital_minor, retained_earnings_minor, reserved_minor) VALUES (?, 100, 100, 0, 0)");
                 PreparedStatement asset = c.prepareStatement("INSERT INTO asset_bindings VALUES (?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement listing = c.prepareStatement("INSERT INTO stock_listings VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement ledger = c.prepareStatement("INSERT INTO escrow_ledger_entries (id, liability_kind, company_id, player_uuid, amount_minor, operation_id, trade_id, occurred_at) VALUES (?, 'COMPANY_TREASURY', ?, NULL, 100, NULL, NULL, ?)") ) {
                companyInsert.setString(1, company.value().toString()); companyInsert.setString(2, "report-company"); companyInsert.setString(3, "Report Company"); companyInsert.setString(4, UUID.randomUUID().toString()); companyInsert.setString(5, "LISTED"); companyInsert.setLong(6, 0); companyInsert.setLong(7, 1000); companyInsert.setInt(8, 5000); companyInsert.setString(9, Instant.EPOCH.toString()); companyInsert.setInt(10, 0); companyInsert.executeUpdate();
                cash.setString(1, company.value().toString()); cash.executeUpdate();
                asset.setString(1, binding.id().toString()); asset.setString(2, company.value().toString()); asset.setString(3, binding.adapterId()); asset.setString(4, binding.externalKey()); asset.setString(5, binding.verifiedOwner().toString()); asset.setString(6, binding.state().name()); asset.setString(7, binding.createdAt().toString()); asset.executeUpdate();
                listing.setString(1, company.value().toString()); listing.setString(2, "BS000001"); listing.setLong(3, 10); listing.setLong(4, 1000); listing.setString(5, Instant.EPOCH.toString()); listing.executeUpdate();
                ledger.setString(1, UUID.randomUUID().toString()); ledger.setString(2, company.value().toString()); ledger.setString(3, Instant.EPOCH.toString()); ledger.executeUpdate();
            }
            return null;
        });
    }
    private static void seedBluechip(Database database) {
        CompanyId bluechip = new CompanyId(UUID.randomUUID());
        database.inTransaction(c -> { try (PreparedStatement company = c.prepareStatement("INSERT INTO companies VALUES (?, ?, ?, ?, 'LISTED', 0, 1000, 5000, ?, 0)"); PreparedStatement listing = c.prepareStatement("INSERT INTO stock_listings VALUES (?, 'BS999999', 10, 1000, ?)"); PreparedStatement bluechips = c.prepareStatement("INSERT INTO bluechip_companies (company_id, industry, system_account_uuid, lower_price_minor, upper_price_minor, model_price_minor, spread_bps, event_sensitivity_bps, payout_bps, next_event_at, next_dividend_at) VALUES (?, 'Test', ?, 1, 3, 2, 0, 0, 0, ?, ?)") ) { String at=Instant.EPOCH.toString(); company.setString(1,bluechip.value().toString());company.setString(2,"bluechip-"+bluechip.value());company.setString(3,"Bluechip");company.setString(4,UUID.randomUUID().toString());company.setString(5,at);company.executeUpdate();listing.setString(1,bluechip.value().toString());listing.setString(2,at);listing.executeUpdate();bluechips.setString(1,bluechip.value().toString());bluechips.setString(2,UUID.randomUUID().toString());bluechips.setString(3,at);bluechips.setString(4,at);bluechips.executeUpdate(); } return null; });
    }
    private static long count(Database database, String sql, CompanyId company) { try (var c = database.dataSource().getConnection(); var s = c.prepareStatement(sql)) { s.setString(1, company.value().toString()); try (var rows = s.executeQuery()) { rows.next(); return rows.getLong(1); } } catch (Exception e) { throw new IllegalStateException(e); } }
}
