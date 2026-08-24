package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Instant;
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

    private BluechipBootstrapService service(Database database, SqlBluechipRepository repository, BluechipConfig config) {
        return new BluechipBootstrapService(config, SYSTEM_ACCOUNT, new SqlCompanyRepository(database.dataSource()),
                new SqlStockListingRepository(database.dataSource()), repository, database, Runnable::run, () -> NOW);
    }

    private static Database migratedDatabase(java.nio.file.Path file) throws Exception {
        Database database = new Database("jdbc:sqlite:" + file);
        database.migrate();
        return database;
    }

    private static BluechipConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", "BC" + index);
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
