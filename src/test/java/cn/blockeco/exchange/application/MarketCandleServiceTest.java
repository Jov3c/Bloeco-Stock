package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MarketCandleServiceTest {
    @Test void closeWritesIdempotentModelPriceCandleWhenNoTradesOccurred() throws Exception {
        var file = Files.createTempFile("blockstock-candle-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var repository = new SqlBluechipRepository(database.dataSource());
            var clock = Instant.parse("2026-08-24T00:00:00Z");
            new BluechipBootstrapService(config(), UUID.fromString("00000000-0000-0000-0000-000000000099"), new SqlCompanyRepository(database.dataSource()), new SqlStockListingRepository(database.dataSource()), repository, database, Runnable::run, () -> clock).initializeMissing().toCompletableFuture().join();
            var service = new MarketCandleService(repository, database, Runnable::run);
            var day = LocalDate.of(2026, 8, 24);

            service.closeTradingDay(day).toCompletableFuture().join();
            service.closeTradingDay(day).toCompletableFuture().join();

            CompanyId company = repository.all().getFirst().companyId();
            var candle = repository.candle(company, day).orElseThrow();
            assertThat(candle.open().minorUnits()).isEqualTo(1_000);
            assertThat(candle.high().minorUnits()).isEqualTo(1_000);
            assertThat(candle.low().minorUnits()).isEqualTo(1_000);
            assertThat(candle.close().minorUnits()).isEqualTo(1_000);
            assertThat(candle.volumeShares()).isZero();
        } finally { Files.deleteIfExists(file); }
    }
    private static BluechipConfig config() { var yaml = new YamlConfiguration(); java.util.List<java.util.Map<String,Object>> rows = new java.util.ArrayList<>(); for (int i=0;i<10;i++) { var row = new java.util.LinkedHashMap<String,Object>(); row.put("code", i == 0 ? "RDT" : "BC" + i); row.put("display-name", "System " + i); row.put("industry", "Industry " + i); row.put("reference-price", "10.00"); row.put("lower-bound", "8.00"); row.put("upper-bound", "12.00"); row.put("total-shares", 1_000_000L); row.put("initial-fund-cash", "100000.00"); row.put("initial-fund-shares", 100_000L); row.put("spread-bps",50); row.put("event-sensitivity-bps",100); row.put("dividend-payout-bps",2000); rows.add(row); } yaml.set("bluechips", rows); return BluechipConfig.load(yaml,2); }
}
