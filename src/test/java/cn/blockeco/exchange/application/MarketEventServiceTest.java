package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MarketEventServiceTest {
    @Test void companyEventPersistsMovesModelAndDecayReturnsItTowardReference() throws Exception {
        var file = Files.createTempFile("blockstock-event-", ".db");
        try (var database = migrated(file)) {
            var clock = new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
            var repository = seeded(database, clock);
            var service = new MarketEventService(repository, database, Runnable::run, clock::now, new Random(7));

            String code = repository.all().getFirst().listing().stockCode();
            var event = service.triggerTestEvent(code, 800).toCompletableFuture().join();
            long moved = repository.findByStockCode(code).orElseThrow().referencePrice().minorUnits();
            long model = repository.findByStockCode(code).orElseThrow().modelPrice().minorUnits();

            assertThat(event.headline()).contains(code);
            assertThat(repository.recentEvents(5)).contains(event);
            assertThat(model).isGreaterThan(moved);
            clock.advance(Duration.ofDays(3));
            service.applyDecay().toCompletableFuture().join();
            assertThat(repository.findByStockCode(code).orElseThrow().modelPrice().minorUnits()).isLessThan(model);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void industryEventChangesOnlyBluechipsInItsIndustry() throws Exception {
        var file = Files.createTempFile("blockstock-industry-event-", ".db");
        try (var database = migrated(file)) {
            var clock = new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
            var repository = seeded(database, clock);
            var service = new MarketEventService(repository, database, Runnable::run, clock::now, new Random(7));
            var relatedCompany = repository.all().stream().filter(c -> c.industry().equals("Industry A")).findFirst().orElseThrow();
            var unrelatedCompany = repository.all().stream().filter(c -> c.industry().equals("Industry B")).findFirst().orElseThrow();
            long related = relatedCompany.modelPrice().minorUnits();
            long unrelated = unrelatedCompany.modelPrice().minorUnits();

            service.triggerTestIndustryEvent("Industry A", 500).toCompletableFuture().join();

            assertThat(repository.findByCompanyId(relatedCompany.companyId()).orElseThrow().modelPrice().minorUnits()).isGreaterThan(related);
            assertThat(repository.findByCompanyId(unrelatedCompany.companyId()).orElseThrow().modelPrice().minorUnits()).isEqualTo(unrelated);
        } finally { Files.deleteIfExists(file); }
    }

    private static Database migrated(java.nio.file.Path file) throws Exception { var database = new Database("jdbc:sqlite:" + file); database.migrate(); return database; }
    private static SqlBluechipRepository seeded(Database database, MutableClock clock) {
        var repository = new SqlBluechipRepository(database.dataSource());
        new BluechipBootstrapService(config(), UUID.fromString("00000000-0000-0000-0000-000000000099"), new SqlCompanyRepository(database.dataSource()), new SqlStockListingRepository(database.dataSource()), repository, database, Runnable::run, clock::now).initializeMissing().toCompletableFuture().join();
        return repository;
    }
    private static BluechipConfig config() {
        var yaml = new YamlConfiguration();
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        rows.add(row("RDT", "Industry A")); rows.add(row("MNR", "Industry B"));
        for (int i = 2; i < 10; i++) rows.add(row("BC" + i, "Industry " + i));
        yaml.set("bluechips", rows); return BluechipConfig.load(yaml, 2);
    }
    private static java.util.Map<String, Object> row(String code, String industry) {
        var row = new java.util.LinkedHashMap<String, Object>(); row.put("code", code); row.put("display-name", code + " Systems"); row.put("industry", industry); row.put("reference-price", "10.00"); row.put("lower-bound", "8.00"); row.put("upper-bound", "12.00"); row.put("total-shares", 1_000_000L); row.put("initial-fund-cash", "100000.00"); row.put("initial-fund-shares", 100_000L); row.put("spread-bps", 50); row.put("event-sensitivity-bps", 100); row.put("dividend-payout-bps", 2_000); return row;
    }
    private static final class MutableClock { private Instant now; MutableClock(Instant now) { this.now = now; } Instant now() { return now; } void advance(Duration amount) { now = now.plus(amount); } }
}
