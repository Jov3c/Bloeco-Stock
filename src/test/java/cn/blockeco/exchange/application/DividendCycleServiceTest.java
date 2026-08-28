package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlAssetBindingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyOperationsRepository;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.domain.finance.OperatingEventKind;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import cn.blockeco.exchange.domain.money.Money;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DividendCycleServiceTest {
    private static final Instant START = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void profitableBluechipCreditsEligibleHoldersExactlyOnceAfterFifteenDays() throws Exception {
        var file = Files.createTempFile("blockstock-dividend-profit-", ".db");
        try (Database database = TestBluechipFixture.migratedDatabase(file)) {
            var repository = new SqlBluechipRepository(database.dataSource());
            TestBluechipFixture.seed(database, repository, START);
            var holder = TestBluechipFixture.addExternalHolder(database, repository.all().getFirst(), 10_000);
            AtomicReference<Instant> now = new AtomicReference<>(START.plus(Duration.ofDays(15)));
            var service = new DividendCycleService(repository, database, Runnable::run, now::get, 100_000);

            var first = service.settleDueRuns().toCompletableFuture().join().stream()
                    .filter(run -> run.companyId().equals(repository.all().getFirst().companyId())).findFirst().orElseThrow();
            long balanceAfterFirst = TestBluechipFixture.securitiesCash(database, holder);
            var retry = service.settleDueRuns().toCompletableFuture().join();
            var restartedRetry = new DividendCycleService(repository, database, Runnable::run, now::get, 100_000)
                    .settleDueRuns().toCompletableFuture().join();

            assertThat(first.distributed().minorUnits()).isPositive();
            assertThat(retry).isEmpty();
            assertThat(restartedRetry).isEmpty();
            assertThat(TestBluechipFixture.securitiesCash(database, holder)).isEqualTo(balanceAfterFirst);
            assertThat(TestBluechipFixture.count(database, "SELECT COUNT(*) FROM dividend_runs")).isEqualTo(10);
            assertThat(TestBluechipFixture.count(database, "SELECT COUNT(*) FROM audit_events WHERE event_type = 'DIVIDEND_PAID'")).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void lossPublishesReportAndDoesNotCreditDividend() throws Exception {
        var file = Files.createTempFile("blockstock-dividend-loss-", ".db");
        try (Database database = TestBluechipFixture.migratedDatabase(file)) {
            var repository = new SqlBluechipRepository(database.dataSource());
            TestBluechipFixture.seed(database, repository, START);
            var company = repository.all().getFirst();
            var holder = TestBluechipFixture.addExternalHolder(database, company, 10_000);
            AtomicReference<Instant> now = new AtomicReference<>(START.plus(Duration.ofDays(15)));
            var service = new DividendCycleService(repository, database, Runnable::run, now::get, -100_000);

            var result = service.settleDueRuns().toCompletableFuture().join().stream()
                    .filter(run -> run.companyId().equals(company.companyId())).findFirst().orElseThrow();

            assertThat(result.distributed().minorUnits()).isZero();
            assertThat(result.paymentCount()).isZero();
            assertThat(TestBluechipFixture.securitiesCash(database, holder)).isZero();
            assertThat(TestBluechipFixture.count(database, "SELECT COUNT(*) FROM company_announcements WHERE company_id = '" + company.companyId().value() + "' AND body LIKE 'NO_DIVIDEND:%'")).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void profitablePlayerCompanyUsesRetainedEarningsAndDebitsItsCashSource() throws Exception {
        var file = Files.createTempFile("blockstock-dividend-player-", ".db");
        try (Database database = TestBluechipFixture.migratedDatabase(file)) {
            var repository = new SqlBluechipRepository(database.dataSource());
            var holder = java.util.UUID.randomUUID();
            CompanyId company = TestBluechipFixture.createListedPlayerCompany(database, START, holder, 40_000);
            AtomicReference<Instant> now = new AtomicReference<>(START.plus(Duration.ofDays(15)));
            var service = new DividendCycleService(repository, database, Runnable::run, now::get, 0);

            var result = service.settleDueRuns().toCompletableFuture().join().stream()
                    .filter(run -> run.companyId().equals(company)).findFirst().orElseThrow();

            assertThat(result.profit().minorUnits()).isEqualTo(40_000);
            assertThat(result.distributed().minorUnits()).isEqualTo(20_000);
            assertThat(TestBluechipFixture.securitiesCash(database, holder)).isEqualTo(20_000);
            assertThat(TestBluechipFixture.companyCash(database, company)).isEqualTo(20_000);
            assertThat(TestBluechipFixture.retainedEarnings(database, company)).isEqualTo(20_000);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void profitablePlayerCompanyDividendKeepsTheEscrowLedgerReconciled() throws Exception {
        var file = Files.createTempFile("blockstock-dividend-player-ledger-", ".db");
        try (Database database = TestBluechipFixture.migratedDatabase(file)) {
            var repository = new SqlBluechipRepository(database.dataSource());
            var holder = java.util.UUID.randomUUID();
            CompanyId company = TestBluechipFixture.createListedPlayerCompany(database, START, holder, 40_000);
            database.inTransaction(connection -> {
                try (var ledger = connection.prepareStatement("INSERT INTO escrow_ledger_entries (id, liability_kind, company_id, player_uuid, amount_minor, operation_id, trade_id, occurred_at) VALUES (?, 'COMPANY_TREASURY', ?, NULL, 40000, NULL, NULL, ?)")) {
                    ledger.setString(1, java.util.UUID.randomUUID().toString());
                    ledger.setString(2, company.value().toString());
                    ledger.setString(3, START.toString());
                    ledger.executeUpdate();
                }
                return null;
            });

            new DividendCycleService(repository, database, Runnable::run, () -> START.plus(Duration.ofDays(15)), 0)
                    .settleDueRuns().toCompletableFuture().join();

            assertThat(new SqlSecuritiesCashRepository(database.dataSource()).reconcile(Money.ofMinor(40_000)).finalLiabilities())
                    .isEqualTo(Money.ofMinor(40_000));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void profitableOperatingIncomeFeedsExistingFifteenDayPlayerDividendOnce() throws Exception {
        var file = Files.createTempFile("blockstock-dividend-operating-income-", ".db");
        try (Database database = TestBluechipFixture.migratedDatabase(file)) {
            var holder = java.util.UUID.randomUUID();
            CompanyId company = TestBluechipFixture.createListedPlayerCompany(database, START, holder, 0);
            var binding = new AssetBinding(java.util.UUID.randomUUID(), company, "compatible-test-source", "sale-terminal",
                    holder, AssetBindingState.ACTIVE, START);
            database.inTransaction(connection -> { new SqlAssetBindingRepository(database.dataSource()).insertActive(connection, binding); return null; });
            var source = new cn.blockeco.exchange.ports.CompanyOperatingEventSource() {
                @Override public String adapterId() { return "compatible-test-source"; }
                @Override public java.util.List<VerifiedOperatingEvent> readSince(AssetBinding ignored, Instant after, Instant through) {
                    return java.util.List.of(new VerifiedOperatingEvent(adapterId(), "completed-sale-1", OperatingEventKind.INCOME,
                            40_000, START.plusSeconds(1), "verified completed sale"));
                }
            };
            new CompanyOperationsService(new SqlAssetBindingRepository(database.dataSource()),
                    new SqlCompanyOperationsRepository(database.dataSource()), database, java.util.List.of(source), () -> START.plusSeconds(2))
                    .ingestDueEvents().toCompletableFuture().join();
            AtomicReference<Instant> now = new AtomicReference<>(START.plus(Duration.ofDays(15)));
            var dividends = new DividendCycleService(new SqlBluechipRepository(database.dataSource()), database, Runnable::run, now::get, 0);

            dividends.settleDueRuns().toCompletableFuture().join();
            dividends.settleDueRuns().toCompletableFuture().join();

            assertThat(TestBluechipFixture.securitiesCash(database, holder)).isEqualTo(20_000);
            assertThat(TestBluechipFixture.count(database, "SELECT COUNT(*) FROM dividend_runs WHERE company_id = '" + company.value() + "'")).isEqualTo(1);
            assertThat(new SqlSecuritiesCashRepository(database.dataSource()).reconcile(Money.ofMinor(40_000)).finalLiabilities())
                    .isEqualTo(Money.ofMinor(40_000));
        } finally { Files.deleteIfExists(file); }
    }
}
