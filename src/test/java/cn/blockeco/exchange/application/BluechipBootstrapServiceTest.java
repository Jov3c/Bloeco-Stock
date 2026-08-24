package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipBootstrapFundingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Instant;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BluechipBootstrapServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final UUID SYSTEM_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Test
    void initializesExactlyTenListingsWithFiniteFundCashAndInventory() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-bootstrap-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipConfig config = config();
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config);

            BluechipBootstrapResult result = service.initializeMissing().toCompletableFuture().join();

            assertThat(result.createdCompanies()).isEqualTo(10);
            assertThat(repository.all()).hasSize(10);
            var first = repository.all().getFirst();
            assertThat(first.fundShares()).isEqualTo(config.definitions().getFirst().initialFundShares());
            assertThat(first.fundCash()).isEqualTo(Money.ofMinor(config.definitions().getFirst().initialFundCash()));
            assertThat(first.systemAccountId()).isEqualTo(SYSTEM_ACCOUNT);
            assertThat(first.listing().stockCode()).startsWith("BS");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void initialSystemLiquidityIsBackedByTheEscrowLedger() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-escrow-ledger-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipBootstrapService service = service(database, new SqlBluechipRepository(database.dataSource()), config());

            service.initializeMissing().toCompletableFuture().join();

            long seededCash = systemCash(database);
            assertThat(new SqlSecuritiesCashRepository(database.dataSource())
                    .reconcile(Money.ofMinor(seededCash)).confirmedDifference()).isEqualTo(Money.zero());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void secondInitializationCreatesNothingAndDoesNotTouchPlayerCompany() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-idempotent-", ".db");
        try (Database database = migratedDatabase(file)) {
            var companies = new SqlCompanyRepository(database.dataSource());
            Company playerCompany = Company.rehydrate(new CompanyId(UUID.randomUUID()), "玩家矿业", Company.normalizeName("玩家矿业"),
                    UUID.randomUUID(), Money.zero(), 1_000, DividendRate.FIFTY, CompanyStatus.PENDING_ASSET_BINDING, NOW);
            database.inTransaction(connection -> { companies.insert(connection, playerCompany); return null; });
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config());

            service.initializeMissing().toCompletableFuture().join();

            assertThat(service.initializeMissing().toCompletableFuture().join().createdCompanies()).isZero();
            assertThat(companies.findById(playerCompany.id()).orElseThrow().displayName()).isEqualTo("玩家矿业");
            assertThat(repository.all()).allSatisfy(company -> {
                assertThat(company.fundCash().minorUnits()).isPositive();
                assertThat(company.fundShares()).isPositive();
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void reinitializationAcceptsTradedFundBalancesAndDoesNotMintCashAgain() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-live-fund-", ".db");
        try (Database database = migratedDatabase(file)) {
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config());
            service.initializeMissing().toCompletableFuture().join();
            long cashAfterSeed = systemCash(database);
            var first = repository.all().getFirst();
            database.inTransaction(connection -> {
                try (PreparedStatement holding = connection.prepareStatement("UPDATE share_holdings SET available_shares = available_shares - 7 WHERE company_id = ? AND holder_uuid = ?")) {
                    holding.setString(1, first.companyId().value().toString()); holding.setString(2, SYSTEM_ACCOUNT.toString()); holding.executeUpdate();
                }
                try (PreparedStatement cash = connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = available_minor - 500 WHERE player_uuid = ?")) {
                    cash.setString(1, SYSTEM_ACCOUNT.toString()); cash.executeUpdate();
                }
                try (PreparedStatement audit = connection.prepareStatement("INSERT INTO bluechip_fund_audit (id, company_id, operation, cash_delta_minor, shares_delta, occurred_at) VALUES (?, ?, 'BLUECHIP_TRADE', -500, -7, ?)")) {
                    audit.setString(1, UUID.randomUUID().toString()); audit.setString(2, first.companyId().value().toString()); audit.setString(3, NOW.toString()); audit.executeUpdate();
                }
                return null;
            });

            assertThat(service.initializeMissing().toCompletableFuture().join().createdCompanies()).isZero();
            assertThat(systemCash(database)).isEqualTo(cashAfterSeed - 500);
            assertThat(repository.all().getFirst().fundShares()).isEqualTo(first.fundShares() - 7);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void refusesCodeChangedConfigurationBeforeCreatingAnotherBluechip() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-code-change-", ".db");
        try (Database database = migratedDatabase(file)) {
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            service(database, repository, config()).initializeMissing().toCompletableFuture().join();

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service(database, repository, config("CHANGED")).initializeMissing().toCompletableFuture().join()))
                    .hasMessageContaining("bluechip metadata does not match configuration");
            assertThat(repository.all()).hasSize(10);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void refusesExtraPersistedBluechipMetadataBeforeCreatingAnything() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-extra-", ".db");
        try (Database database = migratedDatabase(file)) {
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config());
            service.initializeMissing().toCompletableFuture().join();
            insertExtraBluechipMetadata(database);

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.initializeMissing().toCompletableFuture().join()))
                    .hasMessageContaining("bluechip metadata does not match configuration");
            assertThat(repository.bluechipCompanyIds()).hasSize(11);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private BluechipBootstrapService service(Database database, SqlBluechipRepository repository, BluechipConfig config) {
        var fundingRecords = new SqlBluechipBootstrapFundingRepository(database.dataSource());
        var funding = new BluechipBootstrapFundingService(SYSTEM_ACCOUNT, fundingRecords, database, new BluechipBootstrapFundingService.EscrowEconomy() {
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("withdrawn"); }
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("deposited"); }
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("escrow deposited"); }
        }, new cn.blockeco.exchange.ports.MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } }, () -> NOW);
        return new BluechipBootstrapService(config, SYSTEM_ACCOUNT, new SqlCompanyRepository(database.dataSource()),
                new SqlStockListingRepository(database.dataSource()), repository, new SqlSecuritiesCashRepository(database.dataSource()), funding, fundingRecords,
                database, Runnable::run, () -> NOW);
    }

    private static Database migratedDatabase(java.nio.file.Path file) throws Exception {
        Database database = new Database("jdbc:sqlite:" + file);
        database.migrate();
        return database;
    }

    private static long systemCash(Database database) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT available_minor FROM securities_cash_accounts WHERE player_uuid = ?")) {
            statement.setString(1, SYSTEM_ACCOUNT.toString()); try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static void insertExtraBluechipMetadata(Database database) {
        UUID id = UUID.randomUUID();
        database.inTransaction(connection -> {
            try (PreparedStatement company = connection.prepareStatement("INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor, total_shares, dividend_basis_points, created_at) VALUES (?, 'extra bluechip', 'Extra Bluechip', ?, 'LISTED', 0, 1000, 5000, ?)");
                 PreparedStatement listing = connection.prepareStatement("INSERT INTO stock_listings (company_id, stock_code, issue_reference_price_minor, issued_shares, listed_at) VALUES (?, 'BS999999', 1, 1000, ?)");
                 PreparedStatement metadata = connection.prepareStatement("INSERT INTO bluechip_companies (company_id, industry, system_account_uuid, lower_price_minor, upper_price_minor, model_price_minor, spread_bps, event_sensitivity_bps, payout_bps, next_event_at, next_dividend_at) VALUES (?, 'Extra', ?, 1, 3, 2, 0, 0, 0, ?, ?)")) {
                company.setString(1, id.toString()); company.setString(2, UUID.randomUUID().toString()); company.setString(3, NOW.toString()); company.executeUpdate();
                listing.setString(1, id.toString()); listing.setString(2, NOW.toString()); listing.executeUpdate();
                metadata.setString(1, id.toString()); metadata.setString(2, SYSTEM_ACCOUNT.toString()); metadata.setString(3, NOW.toString()); metadata.setString(4, NOW.toString()); metadata.executeUpdate();
            }
            return null;
        });
    }

    private static BluechipConfig config() { return config("BC0"); }
    private static BluechipConfig config(String firstCode) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", index == 0 ? firstCode : "BC" + index);
            entry.put("display-name", "System Company " + index);
            entry.put("industry", "Industry " + index);
            entry.put("reference-price", "10.00"); entry.put("lower-bound", "8.00"); entry.put("upper-bound", "12.00");
            entry.put("total-shares", 1_000_000L); entry.put("initial-fund-cash", "100000.00"); entry.put("initial-fund-shares", 100_000L);
            entry.put("spread-bps", 50); entry.put("event-sensitivity-bps", 100); entry.put("dividend-payout-bps", 2_000);
            entries.add(entry);
        }
        yaml.set("bluechips", entries);
        return BluechipConfig.load(yaml, 2);
    }
}
